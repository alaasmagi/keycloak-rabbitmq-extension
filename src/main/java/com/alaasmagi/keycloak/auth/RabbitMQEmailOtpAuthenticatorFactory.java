package com.alaasmagi.keycloak.auth;

import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
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
 * Register under the ID "rabbitmq-email-otp" - add this step to a browser
 * flow (typically as an ALTERNATIVE/CONDITIONAL second factor, after the
 * username/password step) via Authentication -> Flows.
 *
 * Unlike the other two authenticators in this project, there is no built-in
 * Keycloak flow step being replaced here - this is a wholly new second
 * factor, so it needs to be added to a flow rather than swapped in for an
 * existing step.
 */
public class RabbitMQEmailOtpAuthenticatorFactory implements AuthenticatorFactory {

    private static final Logger LOG = Logger.getLogger(RabbitMQEmailOtpAuthenticatorFactory.class);

    public static final String PROVIDER_ID = "rabbitmq-email-otp";

    private static final AuthenticationExecutionModel.Requirement[] REQUIREMENT_CHOICES = {
            AuthenticationExecutionModel.Requirement.REQUIRED,
            AuthenticationExecutionModel.Requirement.ALTERNATIVE,
            AuthenticationExecutionModel.Requirement.DISABLED
    };

    // Shared connection - created once, reused across all Authenticator
    // instances (same pattern as the other extensions in this project).
    private RabbitMQConnectionManager connectionManager;
    private int validitySeconds;

    @Override
    public Authenticator create(KeycloakSession session) {
        return new RabbitMQEmailOtpAuthenticator(connectionManager, validitySeconds);
    }

    @Override
    public void init(Config.Scope config) {
        String host = getEnv("RABBITMQ_HOST", "rabbitmq");
        int port = Integer.parseInt(getEnv("RABBITMQ_PORT", "5672"));
        String username = getEnv("RABBITMQ_USERNAME", "guest");
        String password = getEnv("RABBITMQ_PASSWORD", "guest");
        String vhost = getEnv("RABBITMQ_VHOST", "/");
        String exchange = getEnv("RABBITMQ_EXCHANGE", "identity-events");
        this.validitySeconds = Integer.parseInt(getEnv("EMAIL_OTP_VALIDITY_SECONDS", "300"));

        LOG.infof("Initializing RabbitMQ email OTP authenticator: host=%s exchange=%s validitySeconds=%d",
                host, exchange, validitySeconds);
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
    public String getDisplayType() {
        return "RabbitMQ Email OTP";
    }

    @Override
    public String getReferenceCategory() {
        return "otp";
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
        return "Emails the user a one-time numeric code via RabbitMQ instead of Keycloak's built-in SMTP email sending, "
                + "and validates it as a second factor.";
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return Collections.emptyList();
    }

    private static String getEnv(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }
}
