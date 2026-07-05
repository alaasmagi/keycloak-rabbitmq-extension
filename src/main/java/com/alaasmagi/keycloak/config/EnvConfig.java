package com.alaasmagi.keycloak.config;

import org.jboss.logging.Logger;

/**
 * Shared helper for reading typed values from environment variables.
 * Used by all factories in this extension so the same parsing/defaulting
 * logic does not need to be copy-pasted into each class.
 */
public final class EnvConfig {

    private static final Logger LOG = Logger.getLogger(EnvConfig.class);

    private EnvConfig() {
    }

    public static String get(String key, String defaultValue) {
        String value = System.getenv(key);
        return (value == null || value.isBlank()) ? defaultValue : value;
    }

    public static int getInt(String key, int defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            LOG.warnf("Invalid integer value for %s='%s', using default %d", key, value, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Reads a double from the environment and validates it is in [0.0, 1.0].
     * Intended for sample-rate style settings.
     */
    public static double getDouble(String key, double defaultValue) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            double parsed = Double.parseDouble(value);
            if (parsed < 0.0d || parsed > 1.0d) {
                LOG.warnf("Value out of [0,1] range for %s='%s', using default %.2f", key, value, defaultValue);
                return defaultValue;
            }
            return parsed;
        } catch (NumberFormatException e) {
            LOG.warnf("Invalid double value for %s='%s', using default %.2f", key, value, defaultValue);
            return defaultValue;
        }
    }
}

