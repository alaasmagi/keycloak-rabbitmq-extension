package com.alaasmagi.keycloak.auth;

import com.alaasmagi.keycloak.events.DefaultEventTypes;
import com.alaasmagi.keycloak.events.RabbitMQEmailEventPayloads;
import com.alaasmagi.keycloak.observability.GlitchTipReporter;
import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationProcessor;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionToken;
import org.keycloak.models.Constants;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.Urls;
import org.keycloak.sessions.AuthenticationSessionCompoundId;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Replaces Keycloak's default "Verify Email" required action. Instead of
 * Keycloak sending the verification email DIRECTLY (via SMTP), this
 * generates the verification token/link in EXACTLY THE SAME WAY as the
 * built-in required action (org.keycloak.authentication.requiredactions.VerifyEmail),
 * but publishes it to RabbitMQ instead. An external email service reads the
 * message and sends the actual email using its own template.
 *
 * Clicking the resulting link still hits Keycloak's own built-in action-token
 * endpoint (VerifyEmailActionTokenHandler), which marks the email as verified -
 * we only replace how the link gets to the user, not how it's validated.
 *
 * Activation: this does NOT activate automatically. In Admin Console:
 *   1. Authentication -> Required Actions -> disable the built-in "Verify Email"
 *   2. Authentication -> Required Actions -> enable "RabbitMQ Verify Email" and
 *      set it as a Default Action (so new users get this one instead)
 * Repeat in every realm where you want this behavior.
 */
public class RabbitMQVerifyEmailRequiredAction implements RequiredActionProvider {

    private static final Logger LOG = Logger.getLogger(RabbitMQVerifyEmailRequiredAction.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Auth-note key that stores the epoch-second at which the last verification email was sent. */
    private static final String LAST_SENT_AUTH_NOTE = "rabbitmq-verify-email-last-sent";
    /** Minimum time in seconds a user must wait before a resend is allowed. */
    private static final int RESEND_COOLDOWN_SECONDS = 60;

    private final RabbitMQConnectionManager connectionManager;

    public RabbitMQVerifyEmailRequiredAction(RabbitMQConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void evaluateTriggers(RequiredActionContext context) {
        // Nothing to evaluate here - this action is triggered explicitly,
        // either as a Default Action for new users, or set on an individual
        // user's required actions list (same as the built-in "Verify Email").
    }

    @Override
    public void requiredActionChallenge(RequiredActionContext context) {
        UserModel user = context.getUser();

        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            // Nothing to verify - let the flow continue.
            context.ignore();
            return;
        }

        if (user.isEmailVerified()) {
            context.success();
            return;
        }

        try {
            RealmModel realm = context.getRealm();
            AuthenticationSessionModel authSession = context.getAuthenticationSession();
            if (user.getEmail().equals(authSession.getAuthNote(Constants.VERIFY_EMAIL_KEY))) {
                challengeVerifyEmailPage(context);
                return;
            }

            int validityInSecs = realm.getActionTokenGeneratedByUserLifespan(VerifyEmailActionToken.TOKEN_TYPE);
            int absoluteExpirationInSecs = (int) (System.currentTimeMillis() / 1000L) + validityInSecs;
            String authSessionEncodedId = AuthenticationSessionCompoundId.fromAuthSession(authSession).getEncodedId();

            VerifyEmailActionToken token = new VerifyEmailActionToken(
                    user.getId(),
                    absoluteExpirationInSecs,
                    authSessionEncodedId,
                    user.getEmail(),
                    authSession.getClient().getClientId()
            );

            String serializedToken = token.serialize(context.getSession(), realm, context.getUriInfo());

            UriBuilder builder = Urls.actionTokenBuilder(
                    context.getUriInfo().getBaseUri(),
                    serializedToken,
                    authSession.getClient().getClientId(),
                    authSession.getTabId(),
                    AuthenticationProcessor.getClientData(context.getSession(), authSession)
            );
            String verifyLink = builder.build(realm.getName()).toString();

            Instant expiresAt = Instant.now().plus(validityInSecs, ChronoUnit.SECONDS);

            boolean published = publishVerifyEmailEvent(realm, user, verifyLink, expiresAt, validityInSecs);
            if (!published) {
                context.failure();
                return;
            }
            authSession.setAuthNote(LAST_SENT_AUTH_NOTE, Long.toString(Instant.now().getEpochSecond()));
            authSession.setAuthNote(Constants.VERIFY_EMAIL_KEY, user.getEmail());

            // Same UX as the built-in action: show a "check your email" page.
            challengeVerifyEmailPage(context);
        } catch (Exception e) {
            LOG.error("Failed to generate/publish email verification event", e);
            GlitchTipReporter.captureException(e, "verify-email.generate", Map.of(
                    "action", DefaultEventTypes.VERIFY_EMAIL,
                    "realmName", context.getRealm().getName()
            ));
            context.failure();
        }
    }

    /**
     * Called when the user explicitly requests a resend from the verify-email page.
     * Rate-limited to once per {@value #RESEND_COOLDOWN_SECONDS} seconds to
     * prevent email flooding.
     */
    @Override
    public void processAction(RequiredActionContext context) {
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        String lastSentRaw = authSession.getAuthNote(LAST_SENT_AUTH_NOTE);
        if (lastSentRaw != null) {
            try {
                long lastSentEpoch = Long.parseLong(lastSentRaw);
                if (Instant.now().getEpochSecond() - lastSentEpoch < RESEND_COOLDOWN_SECONDS) {
                    // Cooldown not elapsed yet - just redisplay the page without resending.
                    challengeVerifyEmailPage(context);
                    return;
                }
            } catch (NumberFormatException ignored) {
                // Malformed note - fall through and resend.
            }
        }
        // Cooldown elapsed (or no prior send recorded) - clear the key so
        // requiredActionChallenge() will send a fresh email.
        authSession.removeAuthNote(Constants.VERIFY_EMAIL_KEY);
        requiredActionChallenge(context);
    }

    private void challengeVerifyEmailPage(RequiredActionContext context) {
        context.challenge(context.form().createResponse(UserModel.RequiredAction.VERIFY_EMAIL));
    }

    private boolean publishVerifyEmailEvent(RealmModel realm, UserModel user, String verifyLink, Instant expiresAt,
                                          int validityInSecs) {
        try {
            Map<String, Object> content = RabbitMQEmailEventPayloads.build(
                    user,
                    "actionLink",
                    verifyLink,
                    expiresAt,
                    validityInSecs
            );

            Map<String, Object> message = RabbitMQEmailEventPayloads.buildMessage(
                    DefaultEventTypes.TYPE_EMAIL,
                    DefaultEventTypes.VERIFY_EMAIL,
                    realm.getName(),
                    content
            );

            return connectionManager.publish(DefaultEventTypes.TYPE_EMAIL, MAPPER.writeValueAsBytes(message));
        } catch (Exception e) {
            LOG.error("Failed to publish email verification event to RabbitMQ", e);
            GlitchTipReporter.captureException(e, "verify-email.publish", Map.of(
                    "action", DefaultEventTypes.VERIFY_EMAIL,
                    "realmName", realm.getName()
            ));
            return false;
        }
    }

    @Override
    public void close() {
        // The connection is not closed here, see RabbitMQConnectionManagerHolder
    }
}
