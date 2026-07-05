package com.alaasmagi.keycloak.events;

import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventListenerProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The factory is created by Keycloak ONCE at server startup, and holds
 * a single shared RabbitMQConnectionManager across all EventListenerProvider
 * instances (which Keycloak creates frequently - repeatedly creating new
 * provider instances is not a problem, since the heavy part - the AMQP
 * connection - is managed separately).
 *
 * It also holds a shared, in-memory "last known enabled state" cache
 * (userId -> enabled), used by RabbitMQEventListenerProvider to detect
 * ban/unban transitions (see the class-level Javadoc there for details
 * and limitations of this approach).
 *
 * Configuration is read from environment variables (simpler and more
 * reliable than Keycloak's own SPI config, which requires editing
 * keycloak.conf):
 *
 *   RABBITMQ_HOST        (default: "rabbitmq")
 *   RABBITMQ_PORT        (default: 5672)
 *   RABBITMQ_USERNAME
 *   RABBITMQ_PASSWORD
 *   RABBITMQ_VHOST       (default: "/")
 *   RABBITMQ_EXCHANGE    (default: "identity-events")
 */
public class RabbitMQEventListenerProviderFactory implements EventListenerProviderFactory {

    private static final Logger LOG = Logger.getLogger(RabbitMQEventListenerProviderFactory.class);

    // Unique ID used under Realm Settings -> Events -> Event Listeners
    public static final String PROVIDER_ID = "rabbitmq-event-listener";

    private RabbitMQConnectionManager connectionManager;
    private final Map<String, Boolean> lastKnownEnabledState = new ConcurrentHashMap<>();

    @Override
    public EventListenerProvider create(KeycloakSession session) {
        return new RabbitMQEventListenerProvider(connectionManager, lastKnownEnabledState, session);
    }

    @Override
    public void init(Config.Scope config) {
        String host = getEnv("RABBITMQ_HOST", "rabbitmq");
        int port = Integer.parseInt(getEnv("RABBITMQ_PORT", "5672"));
        String username = getEnv("RABBITMQ_USERNAME", "guest");
        String password = getEnv("RABBITMQ_PASSWORD", "guest");
        String vhost = getEnv("RABBITMQ_VHOST", "/");
        String exchange = getEnv("RABBITMQ_EXCHANGE", "identity-events");

        LOG.infof("Initializing RabbitMQ event listener: host=%s port=%d vhost=%s exchange=%s",
                host, port, vhost, exchange);

        this.connectionManager = new RabbitMQConnectionManager(host, port, username, password, vhost, exchange);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Nothing further needed
    }

    @Override
    public void close() {
        if (connectionManager != null) {
            connectionManager.close();
        }
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
