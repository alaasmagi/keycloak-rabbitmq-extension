# keycloak-rabbitmq-extension

## Description

* Extension language: English
* Development year: **2026**
* Languages and technologies: **Java 17, Maven, Keycloak SPI, RabbitMQ**
* Keycloak version targeted by this implementation: **26.0.8**

This project is a custom Keycloak extension that publishes identity and email-related events to RabbitMQ. The main idea is that Keycloak does not send user-facing emails directly. Instead, it generates the required data, publishes it to RabbitMQ, and an external email service consumes the messages and sends the actual emails with its own templates.

The extension covers four separate Keycloak integration points:

* **Event Listener** - publishes user, role, client and identity-provider-link events.
* **Password Reset Authenticator** - replaces Keycloak's default reset-email step and publishes the real reset link.
* **Verify Email Required Action** - replaces Keycloak's default verify-email required action and publishes the real verification link.
* **Email OTP Authenticator** - adds an email-based one-time-code second factor.

## How to run

### Prerequisites

* Java 17+
* Maven
* Keycloak 26.0.8
* RabbitMQ
* An external RabbitMQ consumer/email service that sends the actual emails

The Keycloak server should have the following environment variables:

```bash
RABBITMQ_HOST=<your-rabbitmq-host>              # Default: rabbitmq
RABBITMQ_PORT=<your-rabbitmq-port>              # Default: 5672
RABBITMQ_USERNAME=<your-rabbitmq-username>      # Default: guest
RABBITMQ_PASSWORD=<your-rabbitmq-password>      # Default: guest
RABBITMQ_VHOST=<your-rabbitmq-vhost>            # Default: /
RABBITMQ_EXCHANGE=<your-rabbitmq-exchange>      # Default: identity-events

EMAIL_OTP_VALIDITY_SECONDS=<otp-validity-time>  # Default: 300
```

These variables keep RabbitMQ access information outside the source code and make it possible to use different RabbitMQ hosts, virtual hosts and exchanges in different environments.

### Building the extension

After meeting all prerequisites above, the extension can be built via terminal/cmd opened in the root of this project:

```bash
mvn clean package
```

The build creates the extension JAR here:

```bash
target/keycloak-rabbitmq-extension-1.0.0.jar
```

The generated JAR is a shaded JAR. It includes the RabbitMQ Java client, but Keycloak, Jackson and JBoss Logging dependencies are marked as `provided`, because the Keycloak server provides them at runtime.

### Running with Docker (*The Easy way*)

Create a custom Keycloak Docker image that copies the extension JAR into Keycloak's providers directory:

```dockerfile
FROM quay.io/keycloak/keycloak:26.0.8

COPY target/keycloak-rabbitmq-extension-1.0.0.jar /opt/keycloak/providers/

RUN /opt/keycloak/bin/kc.sh build
```

Build the Docker image:

```bash
docker build -t keycloak-rabbitmq-extension .
```

Example Keycloak service configuration:

```yaml
services:
  keycloak:
    image: keycloak-rabbitmq-extension
    environment:
      KC_DB: postgres
      KC_DB_URL: <your-keycloak-db-url>
      KC_DB_USERNAME: <your-keycloak-db-user>
      KC_DB_PASSWORD: <your-keycloak-db-password>

      KEYCLOAK_ADMIN: <your-admin-username>
      KEYCLOAK_ADMIN_PASSWORD: <your-admin-password>

      RABBITMQ_HOST: rabbitmq
      RABBITMQ_PORT: "5672"
      RABBITMQ_USERNAME: <your-rabbitmq-username>
      RABBITMQ_PASSWORD: <your-rabbitmq-password>
      RABBITMQ_VHOST: "/"
      RABBITMQ_EXCHANGE: identity-events
      EMAIL_OTP_VALIDITY_SECONDS: "300"
```

The RabbitMQ exchange is declared automatically by the extension as a durable topic exchange.

## Activation in Keycloak

### Event Listener

The event listener is activated per realm:

1. Open Keycloak Admin Console.
2. Select the realm.
3. Go to **Realm Settings -> Events**.
4. Add `rabbitmq-event-listener` to **Event Listeners**.
5. Save.

### Password Reset Authenticator

This authenticator replaces Keycloak's built-in **Send Reset Email** step:

1. Go to **Authentication -> Flows**.
2. Select the **Reset credentials** flow.
3. Duplicate the flow.
4. In the copied flow, remove the built-in **Send Reset Email** step.
5. Add **RabbitMQ Reset Credential Email**.
6. Set it to **Required**.
7. Go to **Authentication -> Bindings**.
8. Set **Reset Credentials Flow** to the copied flow.

### Verify Email Required Action

This required action replaces Keycloak's built-in **Verify Email** required action:

1. Go to **Authentication -> Required Actions**.
2. Disable the built-in **Verify Email** action.
3. Enable **RabbitMQ Verify Email**.
4. Set **RabbitMQ Verify Email** as a default action if new users should verify email through RabbitMQ.

### Email OTP Authenticator

This authenticator adds a new email-based second factor:

1. Go to **Authentication -> Flows**.
2. Open or duplicate the **Browser** flow.
3. Add **RabbitMQ Email OTP** after the username/password step.
4. Set the requirement to **Required** or **Alternative**, depending on the realm's authentication policy.

All activation steps are realm-specific, so they should be repeated in every realm where RabbitMQ-based behavior is needed.

## Features

* Publishes Keycloak user lifecycle events to RabbitMQ
* Publishes admin-created and self-registered user events
* Publishes user deletion events
* Publishes user update events
* Publishes role assignment and role removal events
* Publishes client deletion events
* Publishes identity-provider-link email events
* Publishes password reset links through RabbitMQ
* Publishes email verification links through RabbitMQ
* Provides email-based OTP authentication
* Uses ISO-8601 UTC timestamps in event messages
* Uses readable `realmName` values instead of internal realm UUIDs where possible

## Design choices

### Keycloak extension design

The project uses Keycloak SPIs instead of modifying Keycloak itself. This keeps the implementation deployable as a provider JAR and makes it possible to install the extension by placing the JAR into Keycloak's `/opt/keycloak/providers` directory.

There are three SPI registration files:

```text
src/main/resources/META-INF/services/org.keycloak.events.EventListenerProviderFactory
src/main/resources/META-INF/services/org.keycloak.authentication.AuthenticatorFactory
src/main/resources/META-INF/services/org.keycloak.authentication.RequiredActionFactory
```

### RabbitMQ design

RabbitMQ publishing is handled through `RabbitMQConnectionManager`.

```java
public class RabbitMQConnectionManager {
    private final String exchangeName;
    private Connection connection;
    private Channel channel;

    public synchronized void publish(String routingKey, byte[] body) {
        if (channel == null || !channel.isOpen()) {
            return;
        }
        channel.basicPublish(exchangeName, routingKey, null, body);
    }
}
```

Each Keycloak factory creates its own `RabbitMQConnectionManager`, and provider/authenticator instances created by that factory reuse it. This avoids opening a new RabbitMQ connection for every Keycloak request.

The exchange is declared automatically with the following settings:

* Type: `topic`
* Durable: `true`
* Auto-delete: `false`

### Event Listener

The event listener is implemented with `RabbitMQEventListenerProvider` and `RabbitMQEventListenerProviderFactory`.

It publishes the following routing keys:

| Routing key | Description |
|---|---|
| `user.created` | User self-registered or was created by an admin |
| `user.deleted` | User deleted their own account or was deleted by an admin |
| `user.updated` | User data was updated by an admin |
| `user.enabled` | User enabled flag changed to `true` |
| `user.disabled` | User enabled flag changed to `false` |
| `user.role.assigned` | Realm or client role was added to the user |
| `user.role.removed` | Realm or client role was removed from the user |
| `client.deleted` | Client was deleted from the realm |
| `email.identity-provider-link` | User triggered identity-provider account-linking email flow |

Keycloak does not have a separate ban/unban event. This implementation treats the user's `enabled` flag as the source of truth and keeps an in-memory cache of the last known enabled state. This makes it possible to publish `user.enabled` and `user.disabled` only when the observed value changes.

If the user has a custom attribute named `banReason`, it is included as a top-level `banReason` field in `user.disabled` and `user.enabled` messages.

Example event listener message:

```json
{
  "eventType": "user.disabled",
  "realmName": "fitness",
  "userId": "13af8b9e-...",
  "timestamp": "2026-07-02T20:45:00Z",
  "banReason": "Spam / abuse",
  "representation": "{\"id\":\"13af8b9e-...\",\"enabled\":false,\"email\":\"user@example.com\"}"
}
```

### Password Reset Authenticator

`RabbitMQPasswordResetAuthenticator` replaces Keycloak's default reset-email authenticator. This is necessary because Keycloak's normal event listener does not expose the actual reset token or link.

The authenticator generates a Keycloak reset credentials action token, builds the real reset link and publishes it to RabbitMQ.

Example message:

```json
{
  "eventType": "email.password-reset",
  "eventSource": "identity",
  "realmName": "fitness",
  "timestamp": "2026-07-02T20:45:00Z",
  "payload": {
    "userId": "13af8b9e-...",
    "email": "user@example.com",
    "resetLink": "https://identity.example.com/realms/fitness/login-actions/action-token?key=...",
    "expiresAt": "2026-07-02T21:45:00Z",
    "expiresInMinutes": 60,
    "locale": "et"
  }
}
```

The validity time comes from Keycloak realm settings:

```java
realm.getActionTokenGeneratedByUserLifespan()
```

### Verify Email Required Action

`RabbitMQVerifyEmailRequiredAction` replaces Keycloak's default verify-email required action. It creates a Keycloak verify email action token, builds the real verification link and publishes it to RabbitMQ.

Clicking the link still uses Keycloak's own action-token endpoint, so this extension only replaces email delivery, not token validation.

Example message:

```json
{
  "eventType": "email.verify",
  "eventSource": "identity",
  "realmName": "fitness",
  "timestamp": "2026-07-02T20:45:00Z",
  "payload": {
    "userId": "13af8b9e-...",
    "email": "user@example.com",
    "verifyLink": "https://identity.example.com/realms/fitness/login-actions/action-token?key=...",
    "expiresAt": "2026-07-02T21:45:00Z",
    "expiresInMinutes": 60,
    "locale": "et"
  }
}
```

The validity time comes from Keycloak realm settings:

```java
realm.getActionTokenGeneratedByUserLifespan(VerifyEmailActionToken.TOKEN_TYPE)
```

### Email OTP Authenticator

`RabbitMQEmailOtpAuthenticator` provides an email-based one-time-code second factor.

The authenticator:

* generates a 6-digit numeric code with `SecureRandom`
* stores the code in the Keycloak authentication session
* stores the expiration timestamp in the Keycloak authentication session
* publishes the code to RabbitMQ
* validates the code submitted through Keycloak's built-in `login-otp.ftl` form

Example message:

```json
{
  "eventType": "email.2fa-otp",
  "eventSource": "identity",
  "realmName": "fitness",
  "timestamp": "2026-07-02T20:45:00Z",
  "payload": {
    "userId": "13af8b9e-...",
    "email": "user@example.com",
    "otpCode": "482913",
    "expiresAt": "2026-07-02T20:50:00Z",
    "expiresInMinutes": 5,
    "locale": "et"
  }
}
```

The OTP validity time is controlled by:

```bash
EMAIL_OTP_VALIDITY_SECONDS=300
```

### Event payload helper

`RabbitMQEmailEventPayloads` keeps email-related messages consistent.

```java
public static Map<String, Object> buildMessage(String eventType, String realmName, Map<String, Object> payload) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("eventType", eventType);
    message.put("eventSource", "identity");
    message.put("realmName", realmName);
    message.put("timestamp", Instant.now().toString());
    message.put("payload", payload);
    return message;
}
```

### Dependency packaging

The Maven Shade plugin packages the RabbitMQ client into the final JAR:

```xml
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>amqp-client</artifactId>
    <version>5.21.0</version>
</dependency>
```

Keycloak dependencies are marked as `provided`, because they are supplied by the running Keycloak server.