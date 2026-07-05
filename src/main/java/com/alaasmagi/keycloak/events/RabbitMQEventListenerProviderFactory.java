package com.alaasmagi.keycloak.events;

import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManagerHolder;
import com.alaasmagi.keycloak.observability.GlitchTipReporter;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The factory is created by Keycloak ONCE at server startup, and holds
 * a reference to the shared {@link RabbitMQConnectionManager} (see
 * {@link RabbitMQConnectionManagerHolder}) across all EventListenerProvider
 * instances (which Keycloak creates frequently - repeatedly creating new
 * provider instances is not a problem, since the heavy part - the AMQP
 * connection - is managed separately).
 *
 * It also holds a shared, in-memory "last known enabled state" cache
 * (userId -> enabled), used by RabbitMQEventListenerProvider to detect
 * ban/unban transitions (see the class-level Javadoc there for details
 * and limitations of this approach). The cache is bounded at
 * {@value #ENABLED_STATE_CACHE_MAX_SIZE} entries (LRU eviction) to
 * prevent unbounded memory growth in large deployments.
 *
 * Configuration is read from environment variables (simpler and more
 * reliable than Keycloak's own SPI config, which requires editing
 * keycloak.conf):
 *
 *   RABBITMQ_HOST        (default: "rabbitmq")
 *   RABBITMQ_PORT        (default: 5672)
 *   RABBITMQ_USERNAME    (default: "guest")
 *   RABBITMQ_PASSWORD    (default: "guest")
 *   RABBITMQ_VHOST       (default: "/")
 *   RABBITMQ_EXCHANGE    (default: "identity-events")
 */
public class RabbitMQEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger LOG = Logger.getLogger(RabbitMQEventListenerProviderFactory.class);
    private static final int ENABLED_STATE_CACHE_MAX_SIZE = 10_000;

    // Unique ID used under Realm Settings -> Events -> Event Listeners
    public static final String PROVIDER_ID = "rabbitmq-event-listener";

    private RabbitMQConnectionManager connectionManager;

    /**
     * Bounded LRU cache: evicts the least-recently-used entry once the map
     * exceeds {@value #ENABLED_STATE_CACHE_MAX_SIZE} entries, preventing
     * unbounded growth in deployments with many users.
     */
    private final Map<String, Boolean> lastKnownEnabledState = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > ENABLED_STATE_CACHE_MAX_SIZE;
                }
            });

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new RabbitMQEventListenerProvider(connectionManager, lastKnownEnabledState, session);
    }

    @Override
    public void init(Config.Scope config) {
        GlitchTipReporter.initFromEnv();
        this.connectionManager = RabbitMQConnectionManagerHolder.acquire();
        LOG.info("RabbitMQ event listener factory initialized");
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing further needed
    }

    @Override
    public void close() {
        RabbitMQConnectionManagerHolder.release();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }
}
