package com.tabletophelper.shared;

/**
 * The user is signed in but may not do this: 403 (D155). Thrown by the access policy, including for
 * acting out of turn. The message is shown to the user.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
