package com.alaasmagi.keycloak.events;

public final class DefaultEventTypes {

    // ---------------------------------------------------------------------
    // Envelope "type" (the category/channel of a message)
    // ---------------------------------------------------------------------
    public static final String TYPE_USER = "user";
    public static final String TYPE_EMAIL = "email";
    public static final String TYPE_CLIENT = "client";

    // ---------------------------------------------------------------------
    // Envelope "source" prefix. The full source is "identity.{realmName}",
    // e.g. "identity.my-realm".
    // ---------------------------------------------------------------------
    public static final String EVENT_SOURCE_PREFIX = "identity";

    // ---------------------------------------------------------------------
    // Envelope "action" (the concrete thing that happened)
    // ---------------------------------------------------------------------
    public static final String USER_CREATED = "user.created";
    public static final String USER_DELETED = "user.deleted";
    public static final String USER_UPDATED = "user.updated";
    public static final String USER_ENABLED = "user.enabled";
    public static final String USER_DISABLED = "user.disabled";
    public static final String USER_ROLE_ASSIGNED = "user.role.assigned";
    public static final String USER_ROLE_REMOVED = "user.role.removed";
    public static final String CLIENT_DELETED = "client.deleted";
    public static final String PASSWORD_RESET = "user.password.reset";
    public static final String VERIFY_EMAIL = "user.verify";
    public static final String EMAIL_OTP = "user.2fa.otp";

    private DefaultEventTypes() {
    }
}
