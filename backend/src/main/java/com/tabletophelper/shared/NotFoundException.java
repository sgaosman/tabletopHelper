package com.tabletophelper.shared;

/**
 * The resource named in the URL does not exist: 404 (D155). A missing ID in the request body is a
 * {@link ValidationException} instead. The message is shown to the user.
 */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
