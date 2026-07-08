package com.alaasmagi.keycloak.events;

import org.junit.jupiter.api.Test;
import org.keycloak.models.UserModel;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RabbitMQEmailEventPayloadsTest {

    @Test
    void buildsEmailPayloadWithLocaleAndMinuteExpiry() {
        UserModel user = user("user-123", "person@example.com", "et");

        Map<String, Object> payload = RabbitMQEmailEventPayloads.build(
                user,
                "resetLink",
                "https://identity.example/reset",
                Instant.parse("2026-07-02T20:45:00Z"),
                3600
        );

        assertEquals("user-123", payload.get("userId"));
        assertEquals("person@example.com", payload.get("email"));
        assertEquals("https://identity.example/reset", payload.get("resetLink"));
        assertEquals("2026-07-02T20:45:00Z", payload.get("expiresAt"));
        assertEquals(60, payload.get("expiresInMinutes"));
        assertEquals("et", payload.get("locale"));
    }

    @Test
    void omitsBlankLocale() {
        UserModel user = user("user-123", "person@example.com", " ");

        Map<String, Object> payload = RabbitMQEmailEventPayloads.build(
                user,
                "otpCode",
                "123456",
                Instant.parse("2026-07-02T20:50:00Z"),
                300
        );

        assertFalse(payload.containsKey("locale"));
    }

    @Test
    void buildsSharedMessageEnvelope() {
        Map<String, Object> message = RabbitMQEventEnvelope.build(
                DefaultEventTypes.USER_UPDATED,
                "my-realm"
        );

        assertEquals(DefaultEventTypes.USER_UPDATED, message.get("eventType"));
        assertEquals(DefaultEventTypes.EVENT_SOURCE, message.get("eventSource"));
        assertEquals("my-realm", message.get("realmName"));
        assertTrue(message.containsKey("timestamp"));
    }

    @Test
    void buildsMessageEnvelope() {
        Map<String, Object> payload = Map.of("userId", "user-123");

        Map<String, Object> message = RabbitMQEmailEventPayloads.buildMessage(
                DefaultEventTypes.EMAIL_OTP,
                "my-realm",
                payload
        );

        assertEquals(DefaultEventTypes.EMAIL_OTP, message.get("eventType"));
        assertEquals(DefaultEventTypes.EVENT_SOURCE, message.get("eventSource"));
        assertEquals("my-realm", message.get("realmName"));
        assertEquals(payload, message.get("payload"));
        assertTrue(message.containsKey("timestamp"));
    }

    private static UserModel user(String id, String email, String locale) {
        return (UserModel) Proxy.newProxyInstance(
                UserModel.class.getClassLoader(),
                new Class<?>[]{UserModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getEmail" -> email;
                    case "getFirstAttribute" -> "locale".equals(args[0]) ? locale : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
