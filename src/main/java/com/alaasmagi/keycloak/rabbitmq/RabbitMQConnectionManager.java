package com.alaasmagi.keycloak.rabbitmq;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.concurrent.TimeoutException;

/**
 * Manages a single shared RabbitMQ connection and channel for the whole
 * Keycloak instance.
 *
 * This class is a singleton whose lifecycle is tied to the *Factory classes
 * (one instance per server), not to individual Provider instances (which get
 * created per request/session - we do not want to open a new AMQP connection
 * for every single event).
 */
public class RabbitMQConnectionManager {

    private static final Logger LOG = Logger.getLogger(RabbitMQConnectionManager.class);

    private final String exchangeName;
    private Connection connection;
    private Channel channel;

    public RabbitMQConnectionManager(String host, int port, String username, String password,
                                      String virtualHost, String exchangeName) {
        this.exchangeName = exchangeName;
        connect(host, port, username, password, virtualHost);
    }

    private void connect(String host, int port, String username, String password, String virtualHost) {
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

            // Declares the exchange idempotently. If the exchange already exists
            // with exactly these settings (topic, durable, not auto-delete), nothing happens.
            // If it exists with different settings, this throws - check the
            // Management UI settings in that case.
            this.channel.exchangeDeclare(exchangeName, "topic", true, false, null);

            LOG.infof("RabbitMQ connection established, exchange '%s' ready", exchangeName);
        } catch (IOException | TimeoutException e) {
            LOG.error("Failed to establish RabbitMQ connection - events will not be published", e);
            this.connection = null;
            this.channel = null;
        }
    }

    /**
     * Publishes a message. Thread-safe (synchronized), since a RabbitMQ Channel
     * is not officially thread-safe for concurrent publishing.
     */
    public synchronized void publish(String routingKey, byte[] body) {
        if (channel == null || !channel.isOpen()) {
            LOG.warnf("RabbitMQ channel not available, dropping event with routing key '%s'", routingKey);
            return;
        }
        try {
            channel.basicPublish(exchangeName, routingKey, null, body);
        } catch (IOException e) {
            LOG.errorf(e, "Failed to publish event with routing key '%s'", routingKey);
        }
    }

    public void close() {
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (IOException | TimeoutException e) {
            LOG.warn("Error while closing RabbitMQ connection", e);
        }
    }
}
