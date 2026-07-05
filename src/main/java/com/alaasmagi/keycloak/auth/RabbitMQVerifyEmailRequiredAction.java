package com.alaasmagi.keycloak.auth;

import com.alaasmagi.keycloak.events.DefaultEventTypes;
import com.alaasmagi.keycloak.events.RabbitMQEmailEventPayloads;
import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.UriBuilder;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationProcessor;
import org.keycloak.authentication.RequiredActionContext;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.authentication.actiontoken.verifyemail.VerifyEmailActionToken;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.services.Urls;
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

    private final RabbitMQConnectionManager connectionManager;
    private final ObjectMapper mapper = new ObjectMapper();

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

            int validityInSecs = realm.getActionTokenGeneratedByUserLifespan(VerifyEmailActionToken.TOKEN_TYPE);
            int absoluteExpirationInSecs = (int) (System.currentTimeMillis() / 1000L) + validityInSecs;
            String authSessionEncodedId = authSession.getParentSession().getId();

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

            publishVerifyEmailEvent(realm, user, verifyLink, expiresAt, validityInSecs);

            // Same UX as the built-in action: show a "check your email" page.
            // login-verify-email.ftl is Keycloak's own base theme template -
            // reused as-is, since it is just a generic instructional page.
            context.challenge(context.form().createForm("login-verify-email.ftl"));
        } catch (Exception e) {
            LOG.error("Failed to generate/publish email verification event", e);
            context.failure();
        }
    }

    @Override
    public void processAction(RequiredActionContext context) {
        // No form input to process - the only way to complete this required
        // action is by clicking the link, which is handled entirely by
        // Keycloak's own VerifyEmailActionTokenHandler, not by this class.
    }

    private void publishVerifyEmailEvent(RealmModel realm, UserModel user, String verifyLink, Instant expiresAt,
                                          int validityInSecs) {
        try {
            Map<String, Object> payload = RabbitMQEmailEventPayloads.build(
                    user,
                    "verifyLink",
                    verifyLink,
                    expiresAt,
                    validityInSecs
            );

            Map<String, Object> message = RabbitMQEmailEventPayloads.buildMessage(
                    DefaultEventTypes.VerifyEmail,
                    realm.getName(),
                    payload
            );

            connectionManager.publish(DefaultEventTypes.VerifyEmail, mapper.writeValueAsBytes(message));
        } catch (Exception e) {
            LOG.error("Failed to publish email verification event to RabbitMQ", e);
        }
    }

    @Override
    public void close() {
        // The connection is not closed here, see RabbitMQConnectionManager
    }
}
