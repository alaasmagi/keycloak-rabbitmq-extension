package com.alaasmagi.keycloak.auth;

import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManager;
import com.alaasmagi.keycloak.rabbitmq.RabbitMQConnectionManagerHolder;
import com.alaasmagi.keycloak.config.EnvConfig;
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
        GlitchTipReporter.initFromEnv();
        this.validitySeconds = EnvConfig.getInt("EMAIL_OTP_VALIDITY_SECONDS", 300);
        this.connectionManager = RabbitMQConnectionManagerHolder.acquire();
        LOG.infof("RabbitMQ email OTP authenticator factory initialized (validitySeconds=%d)", validitySeconds);
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
}
