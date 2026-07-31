package com.alaasmagi.keycloak.events;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Builds the shared message envelope used by every message this project
 * publishes. Shape (fields in exactly this order):
 * <pre>
 * {
 *   "id": "3f1c...-...",              // fresh UUID per message, also the AMQP messageId
 *   "source": "identity-hub",         // always the literal "identity-hub"
 *   "tenant": "my-realm",             // human-readable realm name (never the realm UUID)
 *   "action": "user-created",         // single hyphenated segment (see DefaultEventTypes)
 *   "timestamp": "2026-07-08T12:44:37.408Z", // ISO-8601 UTC, millisecond precision
 *   "contentVersion": "1.0",          // opaque version string, passed through verbatim
 *   "content": { ... }                // per-action payload, added by the caller
 * }
 * </pre>
 *
 * <p>{@code source} must match the shared C# package's
 * {@code DefaultSources.IdentityHub} exactly; the two sides diverging breaks
 * the broker's topic permissions. {@code contentVersion} is a plain string -
 * there is deliberately no version type, parsing or comparison logic here.
 */
public final class RabbitMQEventEnvelope {

    /**
     * The {@code contentVersion} string stamped on every envelope. Bumped by
     * hand when the {@code content} shape changes in a way consumers must
     * distinguish; it is passed through verbatim and never parsed here.
     */
    public static final String CONTENT_VERSION = "1.0";

    private RabbitMQEventEnvelope() {
    }

    /**
     * Builds the envelope up to (but not including) {@code content}. The caller
     * adds its per-action payload under the {@code "content"} key afterwards;
     * because a {@link LinkedHashMap} preserves insertion order, that keeps
     * {@code content} last, matching the documented field order.
     *
     * @param action    the action name (a single hyphenated segment, see
     *                  {@link DefaultEventTypes})
     * @param realmName the human-readable realm name for {@code tenant} (never
     *                  the realm UUID - see the listener's {@code resolveRealmName})
     */
    public static Map<String, Object> build(String action, String realmName) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("id", UUID.randomUUID().toString());
        message.put("source", DefaultEventTypes.SOURCE_IDENTITY_HUB);
        message.put("tenant", realmName);
        message.put("action", action);
        message.put("timestamp", Instant.now().truncatedTo(ChronoUnit.MILLIS).toString());
        message.put("contentVersion", CONTENT_VERSION);
        return message;
    }

    /**
     * Derives the AMQP topic routing key for a message from its envelope
     * fields: {@code {source}.{tenant}.{action}}, e.g.
     * {@code identity-hub.my-realm.user-2fa-otp}. This is the single place a
     * routing key is assembled - it is never built from a literal at a call
     * site, nor derived from the target exchange.
     */
    public static String routingKey(Map<String, Object> envelope) {
        return envelope.get("source") + "." + envelope.get("tenant") + "." + envelope.get("action");
    }
}
