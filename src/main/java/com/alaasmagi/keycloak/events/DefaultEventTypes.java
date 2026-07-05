package com.alaasmagi.keycloak.events;

public final class DefaultEventTypes {
    public static final String UserCreated = "user.created";
    public static final String UserDeleted = "user.deleted";
    public static final String UserUpdated = "user.updated";
    public static final String UserEnabled = "user.enabled";
    public static final String UserDisabled = "user.disabled";
    public static final String UserRoleAssigned = "user.role.assigned";
    public static final String UserRoleRemoved = "user.role.removed";
    public static final String ClientDeleted = "client.deleted";
    public static final String EmailIdentityProviderLink = "email.identity-provider-link";
    public static final String PasswordReset = "email.password-reset";
    public static final String VerifyEmail = "email.verify";
    public static final String EmailOtp = "email.2fa-otp";

    private DefaultEventTypes() {
    }
}
