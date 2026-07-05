package com.alaasmagi.keycloak.auth;

import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManagerHolder;
import com.alaasmagi.keycloak.observability.GlitchTipReporter;
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

    private RabbitMQConnectionManager connectionManager;

    @Override
    public RequiredActionProvider create(KeycloakSession session) {
        return new RabbitMQVerifyEmailRequiredAction(connectionManager);
    }

    @Override
    public void init(Config.Scope config) {
        GlitchTipReporter.initFromEnv();
        this.connectionManager = RabbitMQConnectionManagerHolder.acquire();
        LOG.info("RabbitMQ verify email required action factory initialized");
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // Not needed
    }

    @Override
    public void close() {
        RabbitMQConnectionManagerHolder.release();
    }

    @Override
    public String getId() {
        return PROVIDER_ID;
    }

    @Override
    public String getDisplayText() {
        return "RabbitMQ Verify Email";
    }
}
