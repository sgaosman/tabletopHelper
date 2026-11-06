package com.tabletophelper.rules;

import com.tabletophelper.shared.ConflictException;

/**
 * A formula that can't be read or worked out: bad syntax, a name its context doesn't have, an unknown function, or a
 * division by zero (D169). The loaders check every formula against a context of zeros and fail on one; met at run
 * time it is the stored data that's wrong, so it answers 409.
 */
public class ExpressionException extends ConflictException {

    public ExpressionException(String message) {
        super(message);
    }
}
