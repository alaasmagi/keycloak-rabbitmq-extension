package com.alaasmagi.keycloak.events;

import org.keycloak.models.UserModel;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class RabbitMQEmailEventPayloads {

    private RabbitMQEmailEventPayloads() {
    }

    /**
     * Builds the "content" object for a user-facing email message, e.g.:
     * <pre>
     * {
     *   "userId": "...",
     *   "email": "user@example.com",
     *   "fullName": "Firstname Lastname",
     *   "actionLink": "https://.../action-token?key=...",   // or "otpCode": "482913"
     *   "expiresAt": "2026-07-02T20:50:00Z",
     *   "expiresInMinutes": 10,
     *   "locale": "et"
     * }
     * </pre>
     * The {@code eventParameterName}/{@code eventParameterValue} pair carries the
     * action-specific field ("actionLink" for verify/password-reset, "otpCode"
     * for 2FA).
     */
    public static Map<String, Object> build(UserModel user, String eventParameterName, Object eventParameterValue,
                                            Instant expiresAt, int validityInSecs) {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("userId", user.getId());
        content.put("email", user.getEmail());

        String fullName = fullName(user);
        if (fullName != null) {
            content.put("fullName", fullName);
        }

        content.put(eventParameterName, eventParameterValue);
        content.put("expiresAt", expiresAt.toString());
        content.put("expiresInMinutes", validityInSecs / 60);

        String locale = user.getFirstAttribute("locale");
        if (locale != null && !locale.isBlank()) {
            content.put("locale", locale);
        }

        return content;
    }

    public static Map<String, Object> buildMessage(String type, String action, String realmName,
                                                   Map<String, Object> content) {
        Map<String, Object> message = RabbitMQEventEnvelope.build(type, action, realmName);
        message.put("content", content);
        return message;
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
}
