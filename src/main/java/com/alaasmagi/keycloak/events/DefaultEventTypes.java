package com.alaasmagi.keycloak.events;

public final class DefaultEventTypes {

    // ---------------------------------------------------------------------
    // Envelope "source". Always the literal "identity-hub" - this value must
    // match the shared C# package's DefaultSources.IdentityHub exactly, since
    // the two sides diverging breaks the broker's topic permissions. It is NOT
    // a prefix: nothing may concatenate a realm (or anything else) onto it.
    // ---------------------------------------------------------------------
    public static final String SOURCE_IDENTITY_HUB = "identity-hub";

    // ---------------------------------------------------------------------
    // Envelope "action" (the concrete thing that happened). One hyphenated
    // segment each - these double as the last segment of the routing key
    // (see RabbitMQEventEnvelope.routingKey).
    // ---------------------------------------------------------------------
    public static final String USER_CREATED = "user-created";
    public static final String USER_DELETED = "user-deleted";
    public static final String USER_UPDATED = "user-updated";
    public static final String USER_ENABLED = "user-enabled";
    public static final String USER_DISABLED = "user-disabled";
    public static final String PASSWORD_RESET = "user-password-reset";
    public static final String VERIFY_EMAIL = "user-verify";
    public static final String EMAIL_OTP = "user-2fa-otp";

    private DefaultEventTypes() {
    }
}
