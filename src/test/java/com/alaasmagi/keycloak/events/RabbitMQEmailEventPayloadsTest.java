package com.alaasmagi.keycloak.events;

import org.junit.jupiter.api.Test;
import org.keycloak.models.UserModel;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
        assertEquals(60, content.get("validForMinutes"));
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
    void envelopeHasExactlyTheContractKeySetInOrder() {
        Map<String, Object> content = Map.of("userId", "user-123");

        Map<String, Object> message = RabbitMQEmailEventPayloads.buildMessage(
                DefaultEventTypes.EMAIL_OTP,
                "my-realm",
                content
        );

        // Exact key set AND order, per the contract.
        assertEquals(
                List.of("id", "source", "tenant", "action", "timestamp", "contentVersion", "content"),
                List.copyOf(message.keySet())
        );
        // The old "type" field must be gone entirely.
        assertFalse(message.containsKey("type"));

        assertEquals("identity-hub", message.get("source"));
        assertEquals("my-realm", message.get("tenant"));
        assertEquals("user-2fa-otp", message.get("action"));
        assertEquals("1.0", message.get("contentVersion"));
        assertEquals(content, message.get("content"));
        assertTrue(message.get("id") instanceof String);
        assertFalse(((String) message.get("id")).isBlank());
    }

    @Test
    void sourceIsAlwaysIdentityHubNeverRealmScoped() {
        Map<String, Object> a = RabbitMQEventEnvelope.build(DefaultEventTypes.USER_CREATED, "realm-one");
        Map<String, Object> b = RabbitMQEventEnvelope.build(DefaultEventTypes.USER_CREATED, "realm-two");

        assertEquals("identity-hub", a.get("source"));
        assertEquals("identity-hub", b.get("source"));
    }

    @Test
    void timestampIsMillisecondPrecisionNotNanoseconds() {
        Map<String, Object> message = RabbitMQEventEnvelope.build(DefaultEventTypes.USER_UPDATED, "my-realm");

        String timestamp = (String) message.get("timestamp");
        assertTrue(timestamp.endsWith("Z"));
        // No sub-millisecond digits: re-truncating to millis is a no-op.
        Instant parsed = Instant.parse(timestamp);
        assertEquals(parsed, parsed.truncatedTo(ChronoUnit.MILLIS));
    }

    @Test
    void routingKeyIsSourceTenantActionFromEnvelopeFields() {
        Map<String, Object> message = RabbitMQEventEnvelope.build(DefaultEventTypes.EMAIL_OTP, "my-realm");

        assertEquals("identity-hub.my-realm.user-2fa-otp", RabbitMQEventEnvelope.routingKey(message));
    }

    @Test
    void expirationMillisClampsAlreadyExpiredToZero() {
        assertEquals(0L, RabbitMQEmailEventPayloads.expirationMillis(Instant.now().minusSeconds(60)));
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
