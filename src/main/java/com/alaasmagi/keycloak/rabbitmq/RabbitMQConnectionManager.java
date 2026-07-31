package com.alaasmagi.keycloak.rabbitmq;

import com.alaasmagi.keycloak.observability.GlitchTipReporter;
import com.rabbitmq.client.AMQP;
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
 *
 * <p>The channel runs in publisher-confirm mode: {@link #publish} does not
 * report success until the broker has acked the message, bounded by a timeout
 * so a hung broker cannot block a caller (e.g. a login thread) indefinitely.
 *
 * <p>This class does NOT declare exchanges. The topology (exchanges, queues,
 * bindings) is created and owned outside this extension. Declaring an exchange
 * here with arguments that did not match the existing one would throw
 * PRECONDITION_FAILED and take the channel down, after which nothing would be
 * published.
 */
public class RabbitMQConnectionManager {

    private static final Logger LOG = Logger.getLogger(RabbitMQConnectionManager.class);
    private static final long RECONNECT_INTERVAL_MILLIS = 5000;
    private static final String CONTENT_TYPE_JSON = "application/json";
    private static final int DELIVERY_MODE_PERSISTENT = 2;

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String virtualHost;
    private final String identityExchange;
    private final String emailExchange;
    private final int confirmTimeoutMillis;
    private Connection connection;
    private Channel channel;
    private long lastReconnectAttemptMillis;

    public RabbitMQConnectionManager(String host, int port, String username, String password,
                                      String virtualHost, String identityExchange, String emailExchange,
                                      int confirmTimeoutMillis) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.virtualHost = virtualHost;
        this.identityExchange = identityExchange;
        this.emailExchange = emailExchange;
        this.confirmTimeoutMillis = confirmTimeoutMillis;
        connect();
    }

    /** The exchange user facts (the event listener) publish to. */
    public String identityExchange() {
        return identityExchange;
    }

    /** The exchange email commands (the auth/required-action flows) publish to. */
    public String emailExchange() {
        return emailExchange;
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

            // Publisher confirms: a non-throwing basicPublish only means the
            // client handed the frame off, NOT that the broker accepted it.
            // With confirms on we can wait for the broker's ack (see publish()).
            this.channel.confirmSelect();

            // NOTE: exchanges are intentionally NOT declared here - the topology
            // is created and owned outside this extension (see the class Javadoc).

            LOG.infof("RabbitMQ connection established (publisher confirms on); exchanges '%s' (identity) and '%s' (email)",
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
     * Publishes a message and waits for the broker to confirm it. Thread-safe
     * (synchronized), since a RabbitMQ Channel is not officially thread-safe
     * for concurrent publishing.
     *
     * <p>The exchange and routing key are passed in explicitly by the caller -
     * this class never derives either from the message. The routing key is
     * built once from the envelope fields (see
     * {@code RabbitMQEventEnvelope.routingKey}).
     *
     * <p>Returns {@code true} only once the broker has acked the message.
     * A publish that throws, is nacked, or is not confirmed within the
     * configured timeout returns {@code false}.
     *
     * @param exchange          the target exchange (see {@link #identityExchange()} /
     *                          {@link #emailExchange()})
     * @param routingKey        the topic routing key
     * @param messageId         the envelope {@code id}, set as the AMQP messageId
     * @param expirationMillis  per-message TTL in milliseconds, or {@code null}
     *                          for no expiration. Set for email commands only
     *                          (a code/link delivered after its own expiry is
     *                          worse than one never sent); never for user facts.
     * @param body              the serialized message body
     */
    public synchronized boolean publish(String exchange, String routingKey, String messageId,
                                        Long expirationMillis, byte[] body) {
        if (channel == null || !channel.isOpen()) {
            reconnectIfDue();
        }
        if (channel == null || !channel.isOpen()) {
            LOG.warnf("RabbitMQ channel not available, message with routing key '%s' was not published", routingKey);
            return false;
        }
        try {
            channel.basicPublish(exchange, routingKey, buildProperties(messageId, expirationMillis), body);
            boolean acked = channel.waitForConfirms(confirmTimeoutMillis);
            if (!acked) {
                LOG.warnf("RabbitMQ broker nacked message with routing key '%s' on exchange '%s'",
                        routingKey, exchange);
            }
            return acked;
        } catch (IOException e) {
            LOG.errorf(e, "Failed to publish message with routing key '%s'", routingKey);
            GlitchTipReporter.captureException(e, "rabbitmq.publish", Map.of(
                    "exchange", exchange,
                    "routingKey", routingKey
            ));
            closeCurrentConnection();
            return false;
        } catch (TimeoutException e) {
            LOG.warnf(e, "Timed out after %dms waiting for broker to confirm message with routing key '%s'",
                    confirmTimeoutMillis, routingKey);
            GlitchTipReporter.captureException(e, "rabbitmq.publish.confirm-timeout", Map.of(
                    "exchange", exchange,
                    "routingKey", routingKey
            ));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOG.warnf("Interrupted while waiting for broker to confirm message with routing key '%s'", routingKey);
            return false;
        }
    }

    private AMQP.BasicProperties buildProperties(String messageId, Long expirationMillis) {
        AMQP.BasicProperties.Builder props = new AMQP.BasicProperties.Builder()
                .deliveryMode(DELIVERY_MODE_PERSISTENT)
                .contentType(CONTENT_TYPE_JSON);
        if (messageId != null) {
            props.messageId(messageId);
        }
        if (expirationMillis != null) {
            // RabbitMQ expects the per-message TTL as a string, in milliseconds.
            props.expiration(Long.toString(Math.max(0L, expirationMillis)));
        }
        return props.build();
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
