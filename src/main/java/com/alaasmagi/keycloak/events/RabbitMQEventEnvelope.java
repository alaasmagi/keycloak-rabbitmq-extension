package com.alaasmagi.keycloak.events;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RabbitMQEventEnvelope {

    private RabbitMQEventEnvelope() {
    }

    public static Map<String, Object> build(String eventType, String realmName) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventType", eventType);
        message.put("eventSource", DefaultEventTypes.EVENT_SOURCE);
        message.put("realmName", realmName);
        message.put("timestamp", Instant.now().toString());
        return message;
    }
}
