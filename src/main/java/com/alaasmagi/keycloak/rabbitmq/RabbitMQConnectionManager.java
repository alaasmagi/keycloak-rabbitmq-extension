package com.alaasmagi.keycloak.rabbitmq;

import com.alaasmagi.keycloak.observability.GlitchTipReporter;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Manages a single shared RabbitMQ connection and channel for the whole
 * Keycloak instance.
 * This class is a singleton whose lifecycle is tied to the *Factory classes
 * (one instance per server), not to individual Provider instances (which get
 * created per request/session - we do not want to open a new AMQP connection
 * for every single event).
 */
public class RabbitMQConnectionManager {

    private static final Logger LOG = Logger.getLogger(RabbitMQConnectionManager.class);
    private static final long RECONNECT_INTERVAL_MILLIS = 5000;

    // Envelope "type" value that should be routed to the email exchange.
    // Everything else (user, client, ...) goes to the identity exchange.
    private static final String EMAIL_TYPE = "email";

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;
    private final String identityExchange;
    private final String emailExchange;
    private Connection connection;
    private Channel channel;
    private long lastReconnectAttemptMillis;

    public RabbitMQConnectionManager(String host, int port, String username, String password,
                                      String virtualHost, String identityExchange, String emailExchange) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.virtualHost = virtualHost;
        this.identityExchange = identityExchange;
        this.emailExchange = emailExchange;
        connect();
    }

    private void connect() {
        try {
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(host);
            factory.setPort(port);
            factory.setUsername(username);
            factory.setPassword(password);
            if (virtualHost != null && !virtualHost.isBlank()) {
                factory.setVirtualHost(virtualHost);
            }

            // Automatic recovery if the RabbitMQ connection drops temporarily
            // (e.g. RabbitMQ container restart) - the client will try to reconnect itself.
            factory.setAutomaticRecoveryEnabled(true);
            factory.setNetworkRecoveryInterval(5000);

            this.connection = factory.newConnection("keycloak-rabbitmq-extension");
            this.channel = connection.createChannel();

            // Declares the exchanges idempotently. If an exchange already exists
            // with exactly these settings (topic, durable, not auto-delete), nothing happens.
            // If it exists with different settings, this throws - check the
            // Management UI settings in that case.
            this.channel.exchangeDeclare(identityExchange, "topic", true, false, null);
            this.channel.exchangeDeclare(emailExchange, "topic", true, false, null);

            LOG.infof("RabbitMQ connection established, exchanges '%s' (identity) and '%s' (email) ready",
                    identityExchange, emailExchange);
        } catch (IOException | TimeoutException e) {
            LOG.error("Failed to establish RabbitMQ connection - events will not be published", e);
            GlitchTipReporter.captureException(e, "rabbitmq.connect", Map.of(
                    "identityExchange", identityExchange,
                    "emailExchange", emailExchange,
                    "vhost", safeVirtualHostTag()
            ));
            this.connection = null;
            this.channel = null;
        }
    }

    /**
     * Publishes a message. Thread-safe (synchronized), since a RabbitMQ Channel
     * is not officially thread-safe for concurrent publishing.
     *
     * <p>The envelope {@code type} ("user", "client", "email") is used both as
     * the routing key and to select the target exchange: "email" type messages
     * go to the email exchange, everything else goes to the identity exchange.
     */
    public synchronized boolean publish(String type, byte[] body) {
        String exchangeName = exchangeFor(type);
        if (channel == null || !channel.isOpen()) {
            reconnectIfDue();
        }
        if (channel == null || !channel.isOpen()) {
            LOG.warnf("RabbitMQ channel not available, event with routing key '%s' was not published", type);
            return false;
        }
        try {
            channel.basicPublish(exchangeName, type, null, body);
            return true;
        } catch (IOException e) {
            LOG.errorf(e, "Failed to publish event with routing key '%s'", type);
            GlitchTipReporter.captureException(e, "rabbitmq.publish", Map.of(
                    "exchange", exchangeName,
                    "routingKey", type
            ));
            closeCurrentConnection();
            return false;
        }
    }

    private String exchangeFor(String type) {
        return EMAIL_TYPE.equals(type) ? emailExchange : identityExchange;
    }

    public synchronized void close() {
        closeCurrentConnection();
    }

    private void reconnectIfDue() {
        long now = System.currentTimeMillis();
        if (now - lastReconnectAttemptMillis < RECONNECT_INTERVAL_MILLIS) {
            return;
        }
        lastReconnectAttemptMillis = now;
        closeCurrentConnection();
        connect();
    }

    private void closeCurrentConnection() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (IOException | TimeoutException e) {
            LOG.warn("Error while closing RabbitMQ connection", e);
            GlitchTipReporter.captureException(e, "rabbitmq.close", Map.of(
                    "identityExchange", identityExchange,
                    "emailExchange", emailExchange
            ));
        } finally {
            channel = null;
            connection = null;
        }
    }

    private String safeVirtualHostTag() {
        return virtualHost == null || virtualHost.isBlank() ? "<default>" : virtualHost;
    }
}
