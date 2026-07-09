package com.alaasmagi.keycloak.events;

import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.alaasmagi.keycloak.observability.GlitchTipReporter;
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
import org.keycloak.models.UserModel;

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
 *
 * Every message uses the shared envelope built by {@link RabbitMQEventEnvelope}:
 * a "type" (category, e.g. "user"), a "source" ("identity.{realmName}"), an
 * "action" (e.g. "user.created"), a "timestamp" and a "content" object.
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
 * NOTE on "source" / realm name:
 * All messages published by this project use the human-readable realm name
 * (e.g. "my-realm") inside the "source" field ("identity.my-realm"), never
 * Keycloak's internal realm UUID. Event/AdminEvent objects only give us the
 * UUID, so it is resolved to the human-readable name via the KeycloakSession
 * passed in at provider creation time.
 *
 * NOTE on "timestamp":
 * All messages published by this project use an ISO-8601 UTC string (e.g.
 * "2026-07-02T20:45:00Z") for "timestamp", not epoch milliseconds - this
 * deserializes directly into a .NET DateTime/DateTimeOffset without a custom
 * JSON converter.
 *
 * NOTE on user "content":
 * User messages carry a compact content object - userId, email, username,
 * fullName and locale - resolved by looking the user up in the session. The
 * only exception is user.deleted, whose content is just the userId, since
 * Keycloak no longer has a record for a user that has already been removed.
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
 */
public class RabbitMQEventListenerProvider implements EventListenerProvider {

    private static final Logger LOG = Logger.getLogger(RabbitMQEventListenerProvider.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final RabbitMQConnectionManager connectionManager;
    private final Map<String, Boolean> lastKnownEnabledState;
    private final KeycloakSession session;

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
                publishUserEvent(DefaultEventTypes.USER_CREATED, event.getRealmId(), event.getUserId());
                break;

            case DELETE_ACCOUNT:
                // User deleted THEIR OWN account via the Account Console
                publishUserEvent(DefaultEventTypes.USER_DELETED, event.getRealmId(), event.getUserId());
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
                publishUserEvent(DefaultEventTypes.USER_CREATED, event.getRealmId(), userId);
            } else if (operationType == OperationType.DELETE) {
                publishUserEvent(DefaultEventTypes.USER_DELETED, event.getRealmId(), userId);
                if (userId != null) {
                    lastKnownEnabledState.remove(userId);
                }
            } else if (operationType == OperationType.UPDATE) {
                publishUserEvent(DefaultEventTypes.USER_UPDATED, event.getRealmId(), userId);
                handlePossibleEnabledStateChange(event.getRealmId(), userId, representation);
            }
            return;
        }

        if (resourceType == ResourceType.REALM_ROLE_MAPPING
                || resourceType == ResourceType.CLIENT_ROLE_MAPPING) {
            String userId = extractFirstPathSegment(resourcePath);
            if (operationType == OperationType.CREATE) {
                publishRoleEvent(DefaultEventTypes.USER_ROLE_ASSIGNED, event.getRealmId(), userId,
                        event.getRepresentation());
            } else if (operationType == OperationType.DELETE) {
                publishRoleEvent(DefaultEventTypes.USER_ROLE_REMOVED, event.getRealmId(), userId,
                        event.getRepresentation());
            }
            return;
        }

        if (resourceType == ResourceType.CLIENT && operationType == OperationType.DELETE) {
            // A client (application/service) was deleted from the realm - userId does not apply here
            Map<String, Object> content = new LinkedHashMap<>();
            JsonNode client = parseJson(event.getRepresentation());
            if (client != null) {
                content.put("client", client);
            }
            publish(DefaultEventTypes.TYPE_CLIENT, DefaultEventTypes.CLIENT_DELETED, event.getRealmId(), content);
        }
    }

    // ---------------------------------------------------------------------
    // Helper methods
    // ---------------------------------------------------------------------

    /**
     * Publishes a user lifecycle event. The content is a compact object with
     * userId, email, username, fullName and locale, resolved by looking the
     * user up in the session. For user.deleted the user no longer exists, so
     * the content is just the userId.
     */
    private void publishUserEvent(String action, String realmId, String userId) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("userId", userId);

        if (userId != null && realmId != null) {
            try {
                RealmModel realm = session.realms().getRealm(realmId);
                UserModel user = realm == null ? null : session.users().getUserById(realm, userId);
                if (user != null) {
                    putIfPresent(content, "email", user.getEmail());
                    putIfPresent(content, "username", user.getUsername());
                    putIfPresent(content, "fullName", fullName(user));
                    putIfPresent(content, "locale", user.getFirstAttribute("locale"));
                }
            } catch (Exception e) {
                LOG.errorf(e, "Failed to resolve user %s for action '%s'", userId, action);
                GlitchTipReporter.captureException(e, "event-listener.resolve-user", Map.of(
                        "realmName", resolveRealmName(realmId)
                ));
            }
        }

        publish(DefaultEventTypes.TYPE_USER, action, realmId, content);
    }

    /**
     * Publishes a role-mapping change. The content carries the userId and the
     * raw role representation Keycloak provides (a JSON array of the roles that
     * were assigned/removed).
     */
    private void publishRoleEvent(String action, String realmId, String userId, String representationJson) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("userId", userId);
        JsonNode roles = parseJson(representationJson);
        if (roles != null) {
            content.put("roles", roles);
        }
        publish(DefaultEventTypes.TYPE_USER, action, realmId, content);
    }

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
            JsonNode node = MAPPER.readTree(representationJson);
            if (!node.hasNonNull("enabled")) {
                // Representation did not include the enabled field - nothing to compare.
                return;
            }

            boolean currentEnabled = node.get("enabled").asBoolean();
            Boolean previousEnabled = lastKnownEnabledState.put(userId, currentEnabled);

            boolean stateChanged = previousEnabled == null || !previousEnabled.equals(currentEnabled);
            if (stateChanged) {
                String action = currentEnabled ? DefaultEventTypes.USER_ENABLED : DefaultEventTypes.USER_DISABLED;
                publishUserEvent(action, realmId, userId);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Failed to inspect 'enabled' field on user %s representation", userId);
            GlitchTipReporter.captureException(e, "event-listener.enabled-state-inspect", Map.of(
                    "realmName", resolveRealmName(realmId)
            ));
        }
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
     * (e.g. "my-realm"), so every message published by this project uses a
     * consistent "source" ("identity.{realmName}") instead of the UUID. Falls
     * back to returning the UUID itself if the realm cannot be looked up (e.g.
     * it was deleted in the same transaction) so we never lose the information
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
            GlitchTipReporter.captureException(e, "event-listener.resolve-realm", null);
            return realmId;
        }
    }

    /**
     * Joins the user's first and last name into a single "fullName" string,
     * returning null when neither is set (so the field is omitted entirely).
     */
    private static String fullName(UserModel user) {
        String first = user.getFirstName();
        String last = user.getLastName();
        String full = ((first == null ? "" : first) + " " + (last == null ? "" : last)).trim();
        return full.isEmpty() ? null : full;
    }

    private static void putIfPresent(Map<String, Object> content, String key, String value) {
        if (value != null && !value.isBlank()) {
            content.put(key, value);
        }
    }

    private JsonNode parseJson(String json) {
        if (json == null) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to parse representation JSON");
            return null;
        }
    }

    private void publish(String type, String action, String realmId, Map<String, Object> content) {
        try {
            Map<String, Object> message = RabbitMQEventEnvelope.build(type, action, resolveRealmName(realmId));
            message.put("content", content);

            byte[] body = MAPPER.writeValueAsBytes(message);
            connectionManager.publish(type, body);
        } catch (Exception e) {
            // Publishing an event must NEVER interrupt Keycloak's own action
            // (creating a user, deleting one, etc.) - so we swallow the
            // exception here and just log it.
            LOG.errorf(e, "Failed to build/publish event for action '%s'", action);
            GlitchTipReporter.captureException(e, "event-listener.publish", Map.of(
                    "action", action,
                    "realmName", resolveRealmName(realmId)
            ));
        }
    }

    @Override
    public void close() {
        // The connection is NOT closed here - it belongs to RabbitMQConnectionManagerHolder,
        // which is shared across all provider instances (see the Factory).
    }
}
