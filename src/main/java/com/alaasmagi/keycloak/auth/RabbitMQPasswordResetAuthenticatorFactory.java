package com.alaasmagi.keycloak.auth;

import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManagerHolder;
import com.alaasmagi.keycloak.observability.GlitchTipReporter;
import org.jboss.logging.Logger;
import org.keycloak.Config;
import org.keycloak.authentication.Authenticator;
import org.keycloak.authentication.AuthenticatorFactory;
import org.keycloak.models.AuthenticationExecutionModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.provider.ProviderConfigProperty;

import java.util.Collections;
import java.util.List;

/**
 * Register under the ID "rabbitmq-reset-credential-email" -
 * this step appears under Authentication -> Flows once you duplicate
 * the "Reset credentials" flow and replace the "Send Reset Email" step there.
 */
public class RabbitMQPasswordResetAuthenticatorFactory implements AuthenticatorFactory {

    private static final Logger LOG = Logger.getLogger(RabbitMQPasswordResetAuthenticatorFactory.class);

    public static final String PROVIDER_ID = "rabbitmq-reset-credential-email";

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.DISABLED
    };

    private RabbitMQConnectionManager connectionManager;

    @Override
    public Authenticator create(KeycloakSession session) {
        return new RabbitMQPasswordResetAuthenticator(connectionManager);
    }

    @Override
    public void init(Config.Scope config) {
        GlitchTipReporter.initFromEnv();
        this.connectionManager = RabbitMQConnectionManagerHolder.acquire();
        LOG.info("RabbitMQ password reset authenticator factory initialized");
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
    public String getDisplayType() {
        return "RabbitMQ Reset Credential Email";
    }

    @Override
    public String getReferenceCategory() {
        return "reset-credential-email";
    }

    @Override
    public boolean isConfigurable() {
        return false;
    }

    @Override
    public AuthenticationExecutionModel.Requirement[] getRequirementChoices() {
        return REQUIREMENT_CHOICES;
    }

    @Override
    public boolean isUserSetupAllowed() {
        return false;
    }

    @Override
    public String getHelpText() {
        return "Send password reset link via RabbitMQ instead of Keycloak's built-in SMTP email sending.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return Collections.emptyList();
    }
}
