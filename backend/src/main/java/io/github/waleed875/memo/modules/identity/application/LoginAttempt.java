package io.github.waleed875.memo.modules.identity.application;

/** Result of verifying credentials, independent of HTTP and session storage. */
public record LoginAttempt(Status status, UserProfile user, SessionProof session) {
    public enum Status { AUTHENTICATED, MUST_CHANGE_PASSWORD, INVALID }

    public static LoginAttempt invalid() {
        return new LoginAttempt(Status.INVALID, null, null);
    }

    public static LoginAttempt mustChange(UserProfile user) {
        return new LoginAttempt(Status.MUST_CHANGE_PASSWORD, user, null);
    }

    public static LoginAttempt authenticated(UserProfile user, SessionProof session) {
        return new LoginAttempt(Status.AUTHENTICATED, user, session);
    }
}
