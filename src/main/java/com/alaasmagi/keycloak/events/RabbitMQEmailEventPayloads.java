package com.alaasmagi.keycloak.events;

import org.keycloak.models.UserModel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RabbitMQEmailEventPayloads {

    private RabbitMQEmailEventPayloads() {
    }

    public static Map<String, Object> build(UserModel user, String eventParameterName, Object eventParameterValue,
                                            Instant expiresAt, int validityInSecs) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", user.getId());
        payload.put("email", user.getEmail());
        payload.put(eventParameterName, eventParameterValue);
        payload.put("expiresAt", expiresAt.toString());
        payload.put("expiresInMinutes", validityInSecs / 60);

        String locale = user.getFirstAttribute("locale");
        if (locale != null && !locale.isBlank()) {
            payload.put("locale", locale);
        }

        return payload;
    }

    public static Map<String, Object> buildMessage(String eventType, String realmName, Map<String, Object> payload) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventType", eventType);
        message.put("eventSource", "identity");
        message.put("realmName", realmName);
        message.put("timestamp", Instant.now().toString());
        message.put("payload", payload);
        return message;
    }
}
