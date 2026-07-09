# keycloak-rabbitmq-extension

## Description

* Extension language: English
* Development year: **2026**
* Languages and technologies: **Java 17, Maven, Keycloak SPI, RabbitMQ**
* Keycloak version targeted by this implementation: **26.0.8**

This project is a custom Keycloak extension that publishes identity and email-related events to RabbitMQ. The main idea is that Keycloak does not send user-facing emails directly. Instead, it generates the required data, publishes it to RabbitMQ, and an external email service consumes the messages and sends the actual emails with its own templates.

The extension covers four separate Keycloak integration points:

* **Event Listener** - publishes user, role and client events.
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

GLITCHTIP_DSN=<your-glitchtip-dsn>              # Optional, disabled when unset
GLITCHTIP_ENVIRONMENT=<environment-name>        # Optional, default: production
GLITCHTIP_RELEASE=<release-name>                # Optional, default: keycloak-rabbitmq-extension
GLITCHTIP_SAMPLE_RATE=<0.0-to-1.0>              # Optional, default: 1.0
```

These variables keep RabbitMQ access information outside the source code and make it possible to use different RabbitMQ hosts, virtual hosts and exchanges in different environments.

GlitchTip reporting is optional. If `GLITCHTIP_DSN` is not set, the extension only uses normal Keycloak/JBoss logging.

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
      RABBITMQ_EXCHANGE: identity-hub
      EMAIL_OTP_VALIDITY_SECONDS: "300"

      GLITCHTIP_DSN: <your-glitchtip-dsn>
      GLITCHTIP_ENVIRONMENT: production
      GLITCHTIP_RELEASE: keycloak-rabbitmq-extension
      GLITCHTIP_SAMPLE_RATE: "1.0"
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
* Publishes password reset links through RabbitMQ
* Publishes email verification links through RabbitMQ
* Provides email-based OTP authentication
* Uses ISO-8601 UTC timestamps in event messages
* Uses a readable `identity.{realmName}` value in the `source` field instead of internal realm UUIDs where possible

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

    public synchronized boolean publish(String routingKey, byte[] body) {
        if (channel == null || !channel.isOpen()) {
            reconnectIfDue();
        }
        if (channel == null || !channel.isOpen()) {
            return false;
        }
        channel.basicPublish(exchangeName, routingKey, null, body);
        return true;
    }
}
```

All four factories (event listener, password reset, verify email, email OTP) share a single `RabbitMQConnectionManager` instance via `RabbitMQConnectionManagerHolder`. The holder is reference-counted: each factory acquires the connection in `init()` and releases it in `close()`. The underlying AMQP connection is closed only when the last factory releases it (i.e. on Keycloak shutdown). This avoids opening multiple AMQP connections for a single Keycloak instance.

If RabbitMQ is unavailable when Keycloak starts, the manager retries lazily on later publish attempts. Publishing returns a success flag so user-facing flows can avoid showing a reset, verification or OTP screen when the email message was not actually queued.

The exchange is declared automatically with the following settings:

* Type: `topic`
* Durable: `true`
* Auto-delete: `false`

### GlitchTip error reporting

GlitchTip reporting is handled through `GlitchTipReporter`, which uses the Sentry Java SDK with a GlitchTip DSN.

The integration is disabled by default and becomes active only when `GLITCHTIP_DSN` is set. It reports exceptions from RabbitMQ connection/publish failures, password reset publishing, verify-email publishing, OTP publishing and event-listener serialization/inspection errors.

The extension does not attach user email addresses, OTP codes, reset links, verify links, RabbitMQ credentials or raw Keycloak representations to GlitchTip events. Reported data is limited to exception details plus safe tags such as `operation`, `action`, `routingKey`, `realmName`, `exchange` and `component`.

Supported environment variables:

| Variable | Required | Default | Description |
|---|---:|---|---|
| `GLITCHTIP_DSN` | No | unset | GlitchTip project DSN. Reporting is disabled when this is not set. |
| `GLITCHTIP_ENVIRONMENT` | No | `production` | Environment label shown in GlitchTip. |
| `GLITCHTIP_RELEASE` | No | `keycloak-rabbitmq-extension` | Release label shown in GlitchTip. |
| `GLITCHTIP_SAMPLE_RATE` | No | `1.0` | Error event sample rate from `0.0` to `1.0`. |

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

Keycloak does not have a separate ban/unban event. This implementation treats the user's `enabled` flag as the source of truth and keeps a bounded in-memory LRU cache (up to 10,000 entries) of the last known enabled state. This makes it possible to publish `user.enabled` and `user.disabled` only when the observed value changes.

#### Event listener message contract

Event-listener messages use the shared envelope: a `type` (category), a `source` (`identity.{realmName}`), an `action` (equal to the routing key), a `timestamp` and a `content` object:

```json
{
  "type": "user",
  "source": "identity.my-realm",
  "action": "user.created",
  "timestamp": "2026-07-08T12:44:37.408804148Z",
  "content": {
    "userId": "13af8b9e-...",
    "email": "user@example.com",
    "username": "testUser",
    "fullName": "Test User",
    "locale": "et"
  }
}
```

| Field | Type | Present when | Description |
|---|---|---|---|
| `type` | string | Always | Message category: `user` for user/role events, `client` for `client.deleted`. |
| `source` | string | Always | `identity.{realmName}`, using the human-readable realm name when resolvable (falls back to the realm id). |
| `action` | string | Always | Same value as the RabbitMQ routing key. |
| `timestamp` | string | Always | ISO-8601 UTC timestamp generated by the extension. |
| `content` | object | Always | Event-specific content (see below). |

User `content` fields:

| Field | Type | Present when | Description |
|---|---|---|---|
| `userId` | string | Always | Keycloak user id. |
| `email` | string | User still resolvable | Destination email address. Omitted for `user.deleted`. |
| `username` | string | User still resolvable | Keycloak username. Omitted for `user.deleted`. |
| `fullName` | string | User has a first and/or last name | First and last name joined with a space. |
| `locale` | string | User has non-blank `locale` attribute | Optional locale hint. |

Event-listener structures by routing key:

| Routing key | Structure notes |
|---|---|
| `user.created` | `content` = `{ userId, email, username, fullName, locale }`. |
| `user.deleted` | `content` = `{ userId }` only; the user no longer exists. |
| `user.updated` | `content` = `{ userId, email, username, fullName, locale }`. |
| `user.enabled` | `content` = `{ userId, email, username, fullName, locale }`. |
| `user.disabled` | `content` = `{ userId, email, username, fullName, locale }`. |
| `user.role.assigned` | `content` = `{ userId, roles }`, where `roles` is the role-mapping array Keycloak provides. |
| `user.role.removed` | `content` = `{ userId, roles }`, where `roles` is the role-mapping array Keycloak provides. |
| `client.deleted` | `type` is `client`; `content` = `{ client }` with the client representation when Keycloak provides it. |

Example `user.disabled` message:

```json
{
  "type": "user",
  "source": "identity.my-realm",
  "action": "user.disabled",
  "timestamp": "2026-07-08T12:44:37.408804148Z",
  "content": {
    "userId": "13af8b9e-...",
    "email": "user@example.com",
    "username": "testUser",
    "fullName": "Test User",
    "locale": "et"
  }
}
```

Example `client.deleted` message:

```json
{
  "type": "client",
  "source": "identity.my-realm",
  "action": "client.deleted",
  "timestamp": "2026-07-08T12:44:37.408804148Z",
  "content": {
    "client": {
      "id": "7d4d...",
      "clientId": "my-app"
    }
  }
}
```

### Email message contract

Password reset, verify-email and email OTP messages use the shared envelope with `type` set to `email`:

```json
{
  "type": "email",
  "source": "identity.my-realm",
  "action": "user.password.reset",
  "timestamp": "2026-07-08T12:44:37.408804148Z",
  "content": {
    "userId": "13af8b9e-...",
    "email": "user@example.com",
    "fullName": "Test User",
    "actionLink": "https://identity.example.com/realms/my-realm/login-actions/action-token?key=...",
    "expiresAt": "2026-07-02T21:45:00Z",
    "expiresInMinutes": 60,
    "locale": "et"
  }
}
```

| Field | Type | Present when | Description |
|---|---|---|---|
| `type` | string | Always | Constant value: `email`. |
| `source` | string | Always | `identity.{realmName}`. |
| `action` | string | Always | One of `user.password.reset`, `user.verify` or `user.2fa.otp`. |
| `timestamp` | string | Always | ISO-8601 UTC timestamp generated by the extension. |
| `content` | object | Always | Email-specific content for the external email service. |

Shared `content` fields:

| Field | Type | Present when | Description |
|---|---|---|---|
| `userId` | string | Always | Keycloak user id. |
| `email` | string | Always | Destination email address. |
| `fullName` | string | User has a first and/or last name | First and last name joined with a space. |
| `expiresAt` | string | Always | ISO-8601 UTC expiration timestamp. |
| `expiresInMinutes` | number | Always | Validity seconds divided by 60 using integer division. |
| `locale` | string | User has non-blank `locale` attribute | Optional locale hint for the external email service. |

Action-specific `content` fields:

| Action | Field | Type | Description |
|---|---|---|---|
| `user.password.reset` | `actionLink` | string | Keycloak action-token URL for password reset. |
| `user.verify` | `actionLink` | string | Keycloak action-token URL for email verification. |
| `user.2fa.otp` | `otpCode` | string | Six-digit email OTP code, including leading zeros when generated. |

### Password Reset Authenticator

`RabbitMQPasswordResetAuthenticator` replaces Keycloak's default reset-email authenticator. This is necessary because Keycloak's normal event listener does not expose the actual reset token or link.

The authenticator generates a Keycloak reset credentials action token, builds the real reset link and publishes it to RabbitMQ.

If publishing fails for a real user, the flow returns Keycloak's email-send error page instead of claiming that the reset email was sent. Unknown users and users without an email still receive the generic success message to avoid username enumeration.

Example message:

```json
{
  "type": "email",
  "source": "identity.my-realm",
  "action": "user.password.reset",
  "timestamp": "2026-07-08T12:44:37.408804148Z",
  "content": {
    "userId": "13af8b9e-...",
    "email": "user@example.com",
    "fullName": "Test User",
    "actionLink": "https://identity.example.com/realms/my-realm/login-actions/action-token?key=...",
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

The required action stores a Keycloak authentication-session note after publishing, matching the built-in verify-email behavior so a simple page refresh does not publish another verification email. Explicit resend requests are rate-limited to once every 60 seconds per authentication session.

Example message:

```json
{
  "type": "email",
  "source": "identity.my-realm",
  "action": "user.verify",
  "timestamp": "2026-07-08T12:44:37.408804148Z",
  "content": {
    "userId": "13af8b9e-...",
    "email": "user@example.com",
    "fullName": "Test User",
    "actionLink": "https://identity.example.com/realms/my-realm/login-actions/action-token?key=...",
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
* clears the OTP after success, expiration or too many invalid attempts

If OTP publishing fails, the authenticator does not show the OTP form. Invalid OTP submissions are capped at five attempts; reaching the limit fully denies the authentication (rather than silently resetting the flow).

Example message:

```json
{
  "type": "email",
  "source": "identity.my-realm",
  "action": "user.2fa.otp",
  "timestamp": "2026-07-08T12:44:37.408804148Z",
  "content": {
    "userId": "13af8b9e-...",
    "email": "user@example.com",
    "fullName": "Test User",
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

### Event envelope helper

`RabbitMQEventEnvelope` keeps the common top-level event fields consistent for user/admin events and email events.

```java
public static Map<String, Object> build(String eventType, String realmName) {
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("eventType", eventType);
    message.put("eventSource", DefaultEventTypes.EVENT_SOURCE);
    message.put("realmName", realmName);
    message.put("timestamp", Instant.now().toString());
    return message;
}
```

Email events add a nested `payload` field to this envelope. User/admin events add fields such as `userId`, `representation` and `banReason`.

### Dependency packaging

The Maven Shade plugin packages the RabbitMQ client and Sentry Java SDK into the final JAR:

```xml
<dependency>
    <groupId>com.rabbitmq</groupId>
    <artifactId>amqp-client</artifactId>
    <version>5.21.0</version>
</dependency>

<dependency>
    <groupId>io.sentry</groupId>
    <artifactId>sentry</artifactId>
    <version>${sentry.version}</version>
</dependency>
```

Keycloak dependencies are marked as `provided`, because they are supplied by the running Keycloak server.

### Limitations

This extension is tightly coupled to the Keycloak version configured in `pom.xml`, especially the password-reset and verify-email action-token classes from `keycloak-services`. Review and test those flows whenever the Keycloak version changes.

Event-listener messages are best-effort: normal admin/user lifecycle events are logged if RabbitMQ publishing fails, but the original Keycloak admin action is not rolled back. User-facing email flows are stricter and fail the authentication step when their RabbitMQ message cannot be published.

The `user.enabled` and `user.disabled` events rely on an in-memory last-known-state cache (bounded LRU, up to 10,000 entries). After a Keycloak restart, the first observed update for a user can emit an enabled/disabled event even if the enabled flag did not change in that update.

### CI

The repository includes a GitHub Actions workflow that runs:

```bash
mvn -q clean verify
```
