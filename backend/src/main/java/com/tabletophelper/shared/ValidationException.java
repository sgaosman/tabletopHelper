package com.tabletophelper.shared;

/**
 * The request itself is invalid: a malformed value, or an ID in the body that matches nothing: 400
 * (D155). Wrong login details are reported this way too. The message is shown to the user.
 */
public class ValidationException extends RuntimeException {

    public ValidationException(String message) {
        super(message);
    }
}
