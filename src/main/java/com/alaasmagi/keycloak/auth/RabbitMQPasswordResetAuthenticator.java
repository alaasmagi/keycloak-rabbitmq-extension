package com.alaasmagi.keycloak.auth;

import com.alaasmagi.keycloak.events.DefaultEventTypes;
import com.alaasmagi.keycloak.events.RabbitMQEmailEventPayloads;
import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.authentication.AuthenticationFlowContext;
import org.keycloak.authentication.AuthenticationFlowError;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.actiontoken.resetcred.ResetCredentialsActionToken;
import org.keycloak.events.Details;
import org.keycloak.events.Errors;
import org.keycloak.events.EventBuilder;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.models.utils.FormMessage;
import org.keycloak.services.messages.Messages;
import org.keycloak.sessions.AuthenticationSessionModel;

import java.net.URI;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

/**
 * Replaces Keycloak's default "Send Reset Email" step (ResetCredentialEmail).
 *
 * Difference from the default behavior: instead of Keycloak sending the email
 * DIRECTLY (via SMTP), we generate the token/link in EXACTLY THE SAME WAY as
 * the default authenticator, but publish it to RabbitMQ instead. An external
 * email service reads the message and sends the actual email using its own
 * template.
 *
 * NOTE: this uses Keycloak's INTERNAL class ResetCredentialsActionToken
 * (org.keycloak.authentication.actiontoken.resetcred), which is not a public,
 * stable API. Review this file whenever you upgrade to a new Keycloak
 * version - see README.md.
 */
public class RabbitMQPasswordResetAuthenticator implements Authenticator {

    private static final Logger LOG = Logger.getLogger(RabbitMQPasswordResetAuthenticator.class);

    private final RabbitMQConnectionManager connectionManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public RabbitMQPasswordResetAuthenticator(RabbitMQConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
    }

    @Override
    public void authenticate(AuthenticationFlowContext context) {
        UserModel user = context.getUser();
        AuthenticationSessionModel authSession = context.getAuthenticationSession();
        EventBuilder event = context.getEvent();

        // If the previous step (username form) did not find a user, we must not
        // reveal that - still show the "email sent" message (username enumeration protection).
        if (user == null) {
            genericSuccess(context);
            return;
        }

        if (user.getEmail() == null || user.getEmail().trim().isEmpty()) {
            event.user(user).error(Errors.INVALID_EMAIL);
            LOG.warnf("User %s has no email set, cannot send password reset via RabbitMQ", user.getUsername());
            genericSuccess(context);
            return;
        }

        try {
            RealmModel realm = context.getRealm();
            int validityInSecs = realm.getActionTokenGeneratedByUserLifespan();
            int absoluteExpirationInSecs =
                    (int) (System.currentTimeMillis() / 1000L) + validityInSecs;

            String authSessionEncodedId = authSession.getParentSession().getId();

            ResetCredentialsActionToken token = new ResetCredentialsActionToken(
                    user.getId(),
                    user.getEmail(),
                    absoluteExpirationInSecs,
                    authSessionEncodedId,
                    authSession.getClient().getClientId()
            );

            String serializedToken = token.serialize(context.getSession(), realm, context.getUriInfo());
            URI actionTokenUrl = context.getActionTokenUrl(serializedToken);

            Instant expiresAt = Instant.now().plus(validityInSecs, ChronoUnit.SECONDS);

            publishPasswordResetEvent(realm, user, actionTokenUrl.toString(), expiresAt, validityInSecs);

            event.user(user)
                    .detail(Details.EMAIL, user.getEmail())
                    .detail(Details.CODE_ID, authSession.getParentSession().getId())
                    .success();

            genericSuccess(context);
        } catch (Exception e) {
            LOG.error("Failed to generate/publish password reset event", e);
            context.failure(AuthenticationFlowError.INTERNAL_ERROR);
        }
    }

    private void genericSuccess(AuthenticationFlowContext context) {
        // Same UX as default Keycloak: the user sees a "check your email"
        // message, regardless of whether the user/email actually existed.
        context.forkWithSuccessMessage(new FormMessage(Messages.EMAIL_SENT));
    }

    private void publishPasswordResetEvent(RealmModel realm, UserModel user, String resetLink, Instant expiresAt,
                                            int validityInSecs) {
        try {
            Map<String, Object> payload = RabbitMQEmailEventPayloads.build(
                    user,
                    "resetLink",
                    resetLink,
                    expiresAt,
                    validityInSecs
            );

            Map<String, Object> message = RabbitMQEmailEventPayloads.buildMessage(
                    DefaultEventTypes.PasswordReset,
                    realm.getName(),
                    payload
            );

            connectionManager.publish(DefaultEventTypes.PasswordReset, mapper.writeValueAsBytes(message));
        } catch (Exception e) {
            LOG.error("Failed to publish password reset event to RabbitMQ", e);
        }
    }

    @Override
    public void action(AuthenticationFlowContext context) {
        // This method is never called - authenticate() always calls forkWithSuccessMessage(),
        // so this step never shows a form that would trigger an action.
    }

    @Override
    public boolean requiresUser() {
        return false;
    }

    @Override
    public boolean configuredFor(org.keycloak.models.KeycloakSession session, RealmModel realm, UserModel user) {
        return true;
    }

    @Override
    public void setRequiredActions(org.keycloak.models.KeycloakSession session, RealmModel realm, UserModel user) {
        // Not needed
    }

    @Override
    public void close() {
        // The connection is not closed here, see RabbitMQConnectionManager
    }
}
