package com.tabletophelper.shared;

/**
 * The request is valid, but the current state does not allow it, such as a dead target, a spent
 * resource or an encounter in the wrong status: 409 (D155). The message is shown to the user.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
