package com.alaasmagi.keycloak.rabbitmq;

import com.alaasmagi.keycloak.config.EnvConfig;
import org.jboss.logging.Logger;

/**
 * Holds a single shared {@link RabbitMQConnectionManager} that is reused
 * across ALL factories in this extension (event listener, password reset,
 * email verification, email OTP). Without this, each factory would open its
 * own AMQP connection to RabbitMQ - wasteful and unnecessary since they all
 * publish to the same exchange with the same credentials.
 *
 * <p>Reference-counted: each factory calls {@link #acquire()} in its
 * {@code init()} and {@link #release()} in its {@code close()}. The
 * connection is only closed when the last factory releases it (i.e. when
 * Keycloak shuts down).
 */
public final class RabbitMQConnectionManagerHolder {

    private static final Logger LOG = Logger.getLogger(RabbitMQConnectionManagerHolder.class);

    private static RabbitMQConnectionManager instance;
    private static int refCount = 0;

    private RabbitMQConnectionManagerHolder() {
    }

    /**
     * Returns the shared {@link RabbitMQConnectionManager}, creating it on
     * the first call. Must be paired with a call to {@link #release()}.
     */
    public static synchronized RabbitMQConnectionManager acquire() {
        if (instance == null) {
            String host = EnvConfig.get("RABBITMQ_HOST", "rabbitmq");
            int port = EnvConfig.getInt("RABBITMQ_PORT", 5672);
            String username = EnvConfig.get("RABBITMQ_USERNAME", "guest");
            String password = EnvConfig.get("RABBITMQ_PASSWORD", "guest");
            String vhost = EnvConfig.get("RABBITMQ_VHOST", "/");
            String identityExchange = EnvConfig.get("RABBITMQ_IDENTITY_EXCHANGE", "identity.events");
            String emailExchange = EnvConfig.get("RABBITMQ_EMAIL_EXCHANGE", "email.commands");

            LOG.infof("Creating shared RabbitMQ connection: host=%s port=%d vhost=%s identityExchange=%s emailExchange=%s",
                    host, port, vhost, identityExchange, emailExchange);
            instance = new RabbitMQConnectionManager(host, port, username, password, vhost,
                    identityExchange, emailExchange);
        }
        refCount++;
        LOG.debugf("RabbitMQ connection acquired (ref count: %d)", refCount);
        return instance;
    }

    /**
     * Signals that a factory no longer needs the shared connection. When the
     * reference count reaches zero the underlying AMQP connection is closed.
     */
    public static synchronized void release() {
        refCount = Math.max(0, refCount - 1);
        LOG.debugf("RabbitMQ connection released (ref count: %d)", refCount);
        if (refCount == 0 && instance != null) {
            instance.close();
            instance = null;
            LOG.info("Shared RabbitMQ connection closed");
        }
    }
}

