package com.alaasmagi.keycloak.auth;

import com.alaasmagi.keycloak.events.DefaultEventTypes;
import com.alaasmagi.keycloak.events.RabbitMQEmailEventPayloads;
import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Map;

/**
 * A custom second factor: emails the user a one-time numeric code (instead of
 * requiring an authenticator app, like Keycloak's built-in OTP). Keycloak has
 * no built-in "email OTP" mechanism, so this authenticator handles both
 * halves itself:
 *   - authenticate(): generates the code, stores it in the authentication
 *     session (server-side, not visible to the client), and publishes it to
 *     RabbitMQ instead of sending it via SMTP - exactly the same
 *     "Keycloak never sends emails directly" principle as the password reset
 *     and email verification extensions in this project.
 *   - action(): validates the code the user typed into the form against what
 *     was stored, and checks it hasn't expired.
 *
 * Unlike password reset / email verification, there is no Keycloak-internal
 * action-token class involved here at all - the whole code generation,
 * storage, and validation is implemented in this class, since there is no
 * built-in equivalent to replace.
 *
 * Reuses Keycloak's built-in "login-otp.ftl" theme template (the same one
 * used for TOTP entry) for the code input form, since it just renders a
 * single "otp" input field - no custom theme resources are needed.
 */
public class RabbitMQEmailOtpAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(RabbitMQEmailOtpAuthenticator.class);

    private static final String CODE_AUTH_NOTE = "rabbitmq-email-otp-code";
    private static final String EXPIRES_AUTH_NOTE = "rabbitmq-email-otp-expires";
    private static final String FORM_FIELD_NAME = "otp";
    private static final int CODE_LENGTH = 6;

    private final RabbitMQConnectionManager connectionManager;
    private final int validitySeconds;
    private final ObjectMapper mapper = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public RabbitMQEmailOtpAuthenticator(RabbitMQConnectionManager connectionManager, int validitySeconds) {
        this.connectionManager = connectionManager;
        this.validitySeconds = validitySeconds;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();

        if (user == null) {
            // This authenticator must run after the user has already been
            // identified (e.g. after the username/password step) - if there
            // is no user at this point, something is misconfigured in the flow.
            context.failure(AuthenticationFlowError.UNKNOWN_USER);
            return;
        }

        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            LOG.warnf("User %s has no email set, cannot send email OTP", user.getUsername());
            context.failure(AuthenticationFlowError.INVALID_USER);
            return;
        }

        String code = generateCode();
        long expiresAtEpochSeconds = Instant.now().getEpochSecond() + validitySeconds;

        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        authSession.setAuthNote(CODE_AUTH_NOTE, code);
        authSession.setAuthNote(EXPIRES_AUTH_NOTE, Long.toString(expiresAtEpochSeconds));

        publishOtpEvent(context.getRealm(), user, code, expiresAtEpochSeconds);

        context.challenge(context.form().createForm("login-otp.ftl"));
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String expectedCode = authSession.getAuthNote(CODE_AUTH_NOTE);
        String expiresAtRaw = authSession.getAuthNote(EXPIRES_AUTH_NOTE);

        String submittedCode = context.getHttpRequest().getDecodedFormParameters().getFirst(FORM_FIELD_NAME);

        if (expectedCode == null || expiresAtRaw == null) {
            // No code was ever generated for this session (e.g. a stale/replayed form) - restart.
            context.resetFlow();
            return;
        }

        long expiresAtEpochSeconds = Long.parseLong(expiresAtRaw);
        boolean expired = Instant.now().getEpochSecond() > expiresAtEpochSeconds;
        boolean matches = expectedCode.equals(submittedCode);

        if (expired) {
            authSession.removeAuthNote(CODE_AUTH_NOTE);
            authSession.removeAuthNote(EXPIRES_AUTH_NOTE);
            context.failureChallenge(AuthenticationFlowError.EXPIRED_CODE,
                    context.form().setError("otpCodeExpired").createForm("login-otp.ftl"));
            return;
        }

        if (!matches) {
            context.failureChallenge(AuthenticationFlowError.INVALID_CREDENTIALS,
                    context.form().setError("otpCodeInvalid").createForm("login-otp.ftl"));
            return;
        }

        authSession.removeAuthNote(CODE_AUTH_NOTE);
        authSession.removeAuthNote(EXPIRES_AUTH_NOTE);
        context.success();
    }

    /**
     * Generates a random numeric code of CODE_LENGTH digits (e.g. "482913"),
     * including leading zeros if applicable, using SecureRandom rather than
     * Math.random() since this code is a security credential.
     */
    private String generateCode() {
        int bound = (int) Math.pow(10, CODE_LENGTH);
        int value = random.nextInt(bound);
        return String.format("%0" + CODE_LENGTH + "d", value);
    }

    private void publishOtpEvent(RealmModel realm, UserModel user, String code, long expiresAtEpochSeconds) {
        try {
            Map<String, Object> payload = RabbitMQEmailEventPayloads.build(
                    user,
                    "otpCode",
                    code,
                    Instant.ofEpochSecond(expiresAtEpochSeconds),
                    validitySeconds
            );

            Map<String, Object> message = RabbitMQEmailEventPayloads.buildMessage(
                    DefaultEventTypes.EmailOtp,
                    realm.getName(),
                    payload
            );

            connectionManager.publish(DefaultEventTypes.EmailOtp, mapper.writeValueAsBytes(message));
        } catch (Exception e) {
            LOG.error("Failed to publish email OTP event to RabbitMQ", e);
        }
    }

    @Override
    public boolean requiresUser() {
        return true;
    }

    @Override
    public boolean configuredFor(KeycloakSession session, RealmModel realm, UserModel user) {
        // No per-user setup needed (unlike TOTP, which requires the user to
        // have registered an authenticator app) - a code is generated fresh
        // every time, as long as the user has an email address.
        return user.getEmail() != null && !user.getEmail().trim().isEmpty();
    }

    @Override
    public void setRequiredActions(KeycloakSession session, RealmModel realm, UserModel user) {
        // Not needed
    }

    @Override
    public void close() {
        // The connection is not closed here, see RabbitMQConnectionManager
    }
}
