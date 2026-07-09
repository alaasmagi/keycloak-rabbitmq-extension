package com.alaasmagi.keycloak.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds the shared message envelope used by every message this project
 * publishes. Shape:
 * <pre>
 * {
 *   "type": "user",                 // category/channel (see DefaultEventTypes.TYPE_*)
 *   "source": "identity.my-realm",  // "identity.{realmName}"
 *   "action": "user.created",       // the concrete thing that happened
 *   "timestamp": "2026-07-08T12:44:37.408804148Z",
 *   "content": { ... }              // added by the caller
 * }
 * </pre>
 */
public final class RabbitMQEventEnvelope {

    private RabbitMQEventEnvelope() {
    }

    public static Map<String, Object> build(String type, String action, String realmName) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", type);
        message.put("source", DefaultEventTypes.EVENT_SOURCE_PREFIX + "." + realmName);
        message.put("action", action);
        message.put("timestamp", Instant.now().toString());
        return message;
    }
}
