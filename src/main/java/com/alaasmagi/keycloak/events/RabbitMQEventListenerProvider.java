package com.alaasmagi.keycloak.events;

import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Listens to Keycloak's user and admin events and publishes them to RabbitMQ.
 *
 * Covers:
 *  - user.created / user.deleted   (both self-service and admin-initiated)
 *  - user.updated                  (any profile change)
 *  - user.enabled / user.disabled  (ban/unban - see limitation note below)
 *  - user.role.assigned / user.role.removed
 *  - client.deleted
 *  - email.identity-provider-link
 *
 * IMPORTANT ARCHITECTURE NOTE: Keycloak never sends emails to end users directly
 * in this deployment. Every user-facing email (password reset, email
 * verification, 2FA codes, etc.) is dispatched as a RabbitMQ event that an
 * external email service consumes and turns into an actual email using its
 * own templates. For flows that need a real link/token/code (password reset,
 * email verification), the corresponding Keycloak Authenticator/RequiredAction
 * is replaced entirely by a dedicated extension in this project (see
 * RabbitMQPasswordResetAuthenticator, RabbitMQVerifyEmailRequiredAction) - this
 * generic Event Listener alone cannot supply those, since Keycloak does not
 * expose the actual link/token via the Event details map.
 *
 * NOTE on "realmName" vs "realmId":
 * All messages published by this project (from this Event Listener as well
 * as the Password Reset Authenticator, Verify Email Required Action, and
 * Email OTP Authenticator) use a consistent "realmName" field (e.g.
 * "fitness"), never Keycloak's internal realm UUID. Event/AdminEvent objects
 * only give us the UUID, so it is resolved to the human-readable name via
 * the KeycloakSession passed in at provider creation time.
 *
 * NOTE on "timestamp":
 * All messages published by this project use an ISO-8601 UTC string (e.g.
 * "2026-07-02T20:45:00Z") for "timestamp", not epoch milliseconds - this
 * deserializes directly into a .NET DateTime/DateTimeOffset without a custom
 * JSON converter.
 *
 * NOTE on email:
 * The user's email address is available wherever a "representation" or
 * "payload" is included in a message (it's a normal field on
 * UserRepresentation) - there is no separate top-level "email" field.
 * Consumers that need it should read it from there. It is never available
 * for user.deleted, since Keycloak does not provide a representation for a
 * user that has already been removed.
 *
 * NOTE on user.enabled / user.disabled:
 * Keycloak has no dedicated "ban" concept - the closest equivalent is toggling
 * a user's "Enabled" flag (Admin Console -> Users -> Enabled, or Admin API
 * PUT /users/{id} with enabled=false). This fires the same generic USER/UPDATE
 * admin event as any other profile change, and AdminEvent only exposes the
 * user's state AFTER the update - there is no before/after diff available.
 * To emit a specific user.enabled/user.disabled event only when the enabled
 * flag actually CHANGED (not just its current value on every unrelated
 * update), we keep a small in-memory cache of the last known enabled state
 * per user, and only publish when it differs from the previous observation.
 * This cache is in-memory only and is lost on a Keycloak restart - after a
 * restart, the first update seen for a given user may emit a
 * user.enabled/user.disabled event even if the flag did not actually change
 * at that moment. Acceptable for a hobby-project scale deployment.
 *
 * If a custom user attribute named "banReason" is set on the user, its value
 * is lifted out of the raw representation and included as a top-level
 * "banReason" field on user.disabled/user.enabled messages. Keycloak has no
 * built-in "ban reason" concept - this is purely a convention on top of a
 * regular custom attribute.
 */
public class RabbitMQEventListenerProvider implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(RabbitMQEventListenerProvider.class);

    private final RabbitMQConnectionManager connectionManager;
    private final Map<String, Boolean> lastKnownEnabledState;
    private final KeycloakSession session;
    private final ObjectMapper mapper = new ObjectMapper();

    public RabbitMQEventListenerProvider(RabbitMQConnectionManager connectionManager,
                                          Map<String, Boolean> lastKnownEnabledState,
                                          KeycloakSession session) {
        this.connectionManager = connectionManager;
        this.lastKnownEnabledState = lastKnownEnabledState;
        this.session = session;
    }

    // ---------------------------------------------------------------------
    // User-initiated events (login, register, self-service actions)
    // ---------------------------------------------------------------------
    @Override
    public void onEvent(Event event) {
        if (event.getType() == null) {
            return;
        }

        switch (event.getType()) {
            case REGISTER:
                publish(DefaultEventTypes.UserCreated, event.getRealmId(), event.getUserId(), null);
                break;

            case DELETE_ACCOUNT:
                // User deleted THEIR OWN account via the Account Console
                publish(DefaultEventTypes.UserDeleted, event.getRealmId(), event.getUserId(), null);
                break;

            case SEND_IDENTITY_PROVIDER_LINK:
                publish(DefaultEventTypes.EmailIdentityProviderLink, event.getRealmId(), event.getUserId(), null);
                break;

            // SEND_VERIFY_EMAIL is intentionally NOT handled here. Once the
            // built-in "Verify Email" required action is disabled in favor of
            // RabbitMQVerifyEmailRequiredAction (see that class), this event
            // never fires anyway, since the built-in flow that would trigger
            // it is disabled. See RabbitMQVerifyEmailRequiredAction for the
            // real, link-bearing replacement.

            default:
                // All other events (LOGIN, LOGOUT, REFRESH_TOKEN, etc.) are ignored -
                // add a case here if you need any of them in the future.
                break;
        }
    }

    // ---------------------------------------------------------------------
    // Admin-initiated actions (done via Admin Console or Admin API)
    // ---------------------------------------------------------------------
    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getResourceType() == null || event.getOperationType() == null) {
            return;
        }

        ResourceType resourceType = event.getResourceType();
        OperationType operationType = event.getOperationType();
        String resourcePath = event.getResourcePath();

        if (resourceType == ResourceType.USER) {
            String userId = extractFirstPathSegment(resourcePath);
            String representation = event.getRepresentation();

            if (operationType == OperationType.CREATE) {
                publish(DefaultEventTypes.UserCreated, event.getRealmId(), userId, representation);
            } else if (operationType == OperationType.DELETE) {
                publish(DefaultEventTypes.UserDeleted, event.getRealmId(), userId, null);
                if (userId != null) {
                    lastKnownEnabledState.remove(userId);
                }
            } else if (operationType == OperationType.UPDATE) {
                publish(DefaultEventTypes.UserUpdated, event.getRealmId(), userId, representation);
                handlePossibleEnabledStateChange(event.getRealmId(), userId, representation);
            }
            return;
        }

        if (resourceType == ResourceType.REALM_ROLE_MAPPING
                || resourceType == ResourceType.CLIENT_ROLE_MAPPING) {
            String userId = extractFirstPathSegment(resourcePath);
            if (operationType == OperationType.CREATE) {
                publish(DefaultEventTypes.UserRoleAssigned, event.getRealmId(), userId, event.getRepresentation());
            } else if (operationType == OperationType.DELETE) {
                publish(DefaultEventTypes.UserRoleRemoved, event.getRealmId(), userId, event.getRepresentation());
            }
            return;
        }

        if (resourceType == ResourceType.CLIENT && operationType == OperationType.DELETE) {
            // A client (application/service) was deleted from the realm - userId does not apply here
            publish(DefaultEventTypes.ClientDeleted, event.getRealmId(), null, event.getRepresentation());
        }
    }

    // ---------------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------------

    /**
     * Detects whether a USER/UPDATE admin event represents a change to the
     * user's "enabled" flag (i.e. a ban or unban), by comparing the current
     * value against the last value we observed for this user. Only publishes
     * a user.enabled/user.disabled event when the value actually changed.
     */
    private void handlePossibleEnabledStateChange(String realmId, String userId, String representationJson) {
        if (userId == null || representationJson == null) {
            return;
        }

        try {
            JsonNode node = mapper.readTree(representationJson);
            if (!node.hasNonNull("enabled")) {
                // Representation did not include the enabled field - nothing to compare.
                return;
            }

            boolean currentEnabled = node.get("enabled").asBoolean();
            Boolean previousEnabled = lastKnownEnabledState.put(userId, currentEnabled);

            boolean stateChanged = previousEnabled == null || !previousEnabled.equals(currentEnabled);
            if (stateChanged) {
                String routingKey = currentEnabled ? DefaultEventTypes.UserEnabled : DefaultEventTypes.UserDisabled;
                String banReason = extractBanReason(node);
                publish(routingKey, realmId, userId, representationJson, banReason);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to inspect 'enabled' field on user %s representation", userId);
        }
    }

    /**
     * Reads a custom "banReason" attribute from the user representation, if present.
     * Keycloak stores custom attributes as an object of string arrays, e.g.:
     *   "attributes": { "banReason": ["Spam / abuse"] }
     * Returns null if the attribute is not set.
     */
    private String extractBanReason(JsonNode userRepresentation) {
        JsonNode attributes = userRepresentation.get("attributes");
        if (attributes == null || !attributes.has("banReason")) {
            return null;
        }
        JsonNode reasonArray = attributes.get("banReason");
        if (reasonArray.isArray() && reasonArray.size() > 0) {
            return reasonArray.get(0).asText(null);
        }
        return null;
    }

    /**
     * AdminEvent.getResourcePath() formats we encounter here:
     *   "users/{userId}"
     *   "users/{userId}/role-mappings/realm"
     *   "users/{userId}/role-mappings/clients/{clientUuid}"
     * The first segment is always the one we need.
     */
    private String extractFirstPathSegment(String resourcePath) {
        if (resourcePath == null || resourcePath.isBlank()) {
            return null;
        }
        String[] parts = resourcePath.split("/");
        return parts.length > 1 ? parts[1] : null;
    }

    /**
     * Resolves Keycloak's internal realm UUID to its human-readable name
     * (e.g. "fitness"), so every message published by this project uses a
     * consistent "realmName" field instead of the UUID. Falls back to
     * returning the UUID itself if the realm cannot be looked up (e.g. it
     * was deleted in the same transaction) so we never lose the information
     * entirely.
     */
    private String resolveRealmName(String realmId) {
        if (realmId == null) {
            return null;
        }
        try {
            RealmModel realm = session.realms().getRealm(realmId);
            return realm != null ? realm.getName() : realmId;
        } catch (Exception e) {
            LOG.errorf(e, "Failed to resolve realm name for realm id '%s'", realmId);
            return realmId;
        }
    }

    private void publish(String routingKey, String realmId, String userId, String representationJson) {
        publish(routingKey, realmId, userId, representationJson, null);
    }

    private void publish(String routingKey, String realmId, String userId, String representationJson,
                          String banReason) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("eventType", routingKey);
            message.put("realmName", resolveRealmName(realmId));
            message.put("userId", userId);
            message.put("timestamp", Instant.now().toString());
            if (banReason != null) {
                message.put("banReason", banReason);
            }
            if (representationJson != null) {
                // Keycloak already provides this as a JSON string - keep it raw,
                // the consumer can parse it further if it needs the details
                // (including the user's email, which lives here rather than
                // as a separate top-level field).
                message.put("representation", representationJson);
            }

            byte[] body = mapper.writeValueAsBytes(message);
            connectionManager.publish(routingKey, body);
        } catch (Exception e) {
            // Publishing an event must NEVER interrupt Keycloak's own action
            // (creating a user, deleting one, etc.) - so we swallow the
            // exception here and just log it.
            LOG.errorf(e, "Failed to build/publish event for routing key '%s'", routingKey);
        }
    }

    @Override
    public void close() {
        // The connection is NOT closed here - it belongs to RabbitMQConnectionManager,
        // which is shared across all provider instances (see the Factory).
    }
}
