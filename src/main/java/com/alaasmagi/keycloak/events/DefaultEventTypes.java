package com.alaasmagi.keycloak.events;

public final class DefaultEventTypes {
    public static final String EVENT_SOURCE = "identity-hub";

    public static final String USER_CREATED = "user.created";
    public static final String USER_DELETED = "user.deleted";
    public static final String USER_UPDATED = "user.updated";
    public static final String USER_ENABLED = "user.enabled";
    public static final String USER_DISABLED = "user.disabled";
    public static final String USER_ROLE_ASSIGNED = "user.role.assigned";
    public static final String USER_ROLE_REMOVED = "user.role.removed";
    public static final String CLIENT_DELETED = "client.deleted";
    public static final String EMAIL_IDENTITY_PROVIDER_LINK = "email.identity-provider-link";
    public static final String PASSWORD_RESET = "email.password-reset";
    public static final String VERIFY_EMAIL = "email.verify";
    public static final String EMAIL_OTP = "email.2fa-otp";

    private DefaultEventTypes() {
    }
}
