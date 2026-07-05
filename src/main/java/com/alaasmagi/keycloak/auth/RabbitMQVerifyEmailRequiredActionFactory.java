package com.alaasmagi.keycloak.auth;

import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.RequiredActionFactory;
import org.keycloak.authentication.RequiredActionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

/**
 * Register under the ID "rabbitmq-verify-email" - this required action
 * appears under Authentication -> Required Actions once installed. To use
 * it instead of Keycloak's built-in "Verify Email":
 *   1. Disable the built-in "Verify Email" required action
 *   2. Enable "RabbitMQ Verify Email" and set it as a Default Action
 * Repeat in every realm where you want this behavior.
 */
public class RabbitMQVerifyEmailRequiredActionFactory implements RequiredActionFactory {

    private static final Logger LOG = Logger.getLogger(RabbitMQVerifyEmailRequiredActionFactory.class);

    public static final String PROVIDER_ID = "rabbitmq-verify-email";

    // Shared connection - created once, reused across all RequiredActionProvider
    // instances (same pattern as the other extensions in this project).
    private RabbitMQConnectionManager connectionManager;

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return new RabbitMQVerifyEmailRequiredAction(connectionManager);
    }

    @Override
    public void init(Config.Scope config) {
        String host = getEnv("RABBITMQ_HOST", "rabbitmq");
        int port = Integer.parseInt(getEnv("RABBITMQ_PORT", "5672"));
        String username = getEnv("RABBITMQ_USERNAME", "guest");
        String password = getEnv("RABBITMQ_PASSWORD", "guest");
        String vhost = getEnv("RABBITMQ_VHOST", "/");
        String exchange = getEnv("RABBITMQ_EXCHANGE", "identity-events");

        LOG.infof("Initializing RabbitMQ verify email required action: host=%s exchange=%s", host, exchange);
        this.connectionManager = new RabbitMQConnectionManager(host, port, username, password, vhost, exchange);
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Not needed
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

    @Override
    public String getDisplayText() {
        return "RabbitMQ Verify Email";
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
