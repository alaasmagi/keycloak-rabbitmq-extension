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
    void buildsEmailContentWithFullNameLocaleAndMinuteExpiry() {
        UserModel user = user("user-123", "person@example.com", "Test", "User", "et");

        Map<String, Object> content = RabbitMQEmailEventPayloads.build(
                user,
                "actionLink",
                "https://identity.example/reset",
                Instant.parse("2026-07-02T20:45:00Z"),
                3600
        );

        assertEquals("user-123", content.get("userId"));
        assertEquals("person@example.com", content.get("email"));
        assertEquals("Test User", content.get("fullName"));
        assertEquals("https://identity.example/reset", content.get("actionLink"));
        assertEquals("2026-07-02T20:45:00Z", content.get("expiresAt"));
        assertEquals(60, content.get("expiresInMinutes"));
        assertEquals("et", content.get("locale"));
    }

    @Test
    void omitsBlankLocaleAndFullName() {
        UserModel user = user("user-123", "person@example.com", null, null, " ");

        Map<String, Object> content = RabbitMQEmailEventPayloads.build(
                user,
                "otpCode",
                "123456",
                Instant.parse("2026-07-02T20:50:00Z"),
                300
        );

        assertEquals("123456", content.get("otpCode"));
        assertFalse(content.containsKey("locale"));
        assertFalse(content.containsKey("fullName"));
    }

    @Test
    void buildsSharedEnvelope() {
        Map<String, Object> message = RabbitMQEventEnvelope.build(
                DefaultEventTypes.TYPE_USER,
                DefaultEventTypes.USER_UPDATED,
                "my-realm"
        );

        assertEquals(DefaultEventTypes.TYPE_USER, message.get("type"));
        assertEquals("identity.my-realm", message.get("source"));
        assertEquals(DefaultEventTypes.USER_UPDATED, message.get("action"));
        assertTrue(message.containsKey("timestamp"));
    }

    @Test
    void buildsMessageEnvelopeWithContent() {
        Map<String, Object> content = Map.of("userId", "user-123");

        Map<String, Object> message = RabbitMQEmailEventPayloads.buildMessage(
                DefaultEventTypes.TYPE_EMAIL,
                DefaultEventTypes.EMAIL_OTP,
                "my-realm",
                content
        );

        assertEquals(DefaultEventTypes.TYPE_EMAIL, message.get("type"));
        assertEquals("identity.my-realm", message.get("source"));
        assertEquals(DefaultEventTypes.EMAIL_OTP, message.get("action"));
        assertEquals(content, message.get("content"));
        assertTrue(message.containsKey("timestamp"));
    }

    private static UserModel user(String id, String email, String firstName, String lastName, String locale) {
        return (UserModel) Proxy.newProxyInstance(
                UserModel.class.getClassLoader(),
                new Class<?>[]{UserModel.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> id;
                    case "getEmail" -> email;
                    case "getFirstName" -> firstName;
                    case "getLastName" -> lastName;
                    case "getFirstAttribute" -> "locale".equals(args[0]) ? locale : null;
                    default -> throw new UnsupportedOperationException(method.getName());
                }
        );
    }
}
