package com.alaasmagi.keycloak.observability;

import com.alaasmagi.keycloak.config.EnvConfig;
import io.sentry.Sentry;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Optional GlitchTip/Sentry integration.
 *
 * The reporter is disabled unless GLITCHTIP_DSN is set. Callers should only
 * pass safe tags/context: no email addresses, action-token links, OTP codes,
 * raw representations, passwords or RabbitMQ credentials.
 */
public final class GlitchTipReporter {

    private static final Logger LOG = Logger.getLogger(GlitchTipReporter.class);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static volatile boolean enabled;

    private GlitchTipReporter() {
    }

    public static void initFromEnv() {
        if (!INITIALIZED.compareAndSet(false, true)) {
            return;
        }

        String dsn = EnvConfig.get("GLITCHTIP_DSN", null);
        if (dsn == null) {
            enabled = false;
            LOG.debug("GlitchTip reporting disabled, GLITCHTIP_DSN is not set");
            return;
        }

        String environment = EnvConfig.get("GLITCHTIP_ENVIRONMENT", "production");
        String release = EnvConfig.get("GLITCHTIP_RELEASE", "keycloak-rabbitmq-extension");
        double sampleRate = EnvConfig.getDouble("GLITCHTIP_SAMPLE_RATE", 1.0d);

        try {
            Sentry.init(options -> {
                options.setDsn(dsn);
                options.setEnvironment(environment);
                options.setRelease(release);
                options.setSampleRate(sampleRate);
                options.setSendDefaultPii(false);
                options.setTag("component", "keycloak-rabbitmq-extension");
            });
            enabled = true;
            LOG.info("GlitchTip reporting enabled");
        } catch (Exception e) {
            enabled = false;
            LOG.warn("Failed to initialize GlitchTip reporting", e);
        }
    }

    public static void captureException(Throwable throwable, String operation, Map<String, String> tags) {
        if (!enabled || throwable == null) {
            return;
        }

        try {
            Sentry.captureException(throwable, scope -> {
                scope.setTag("operation", operation);
                if (tags != null) {
                    tags.forEach(scope::setTag);
                }
            });
        } catch (Exception e) {
            LOG.debug("Failed to report exception to GlitchTip", e);
        }
    }
}
