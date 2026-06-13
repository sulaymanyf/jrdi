package io.jrdi.resolver;

import java.io.IOException;

public class ResolverException extends RuntimeException {

    public ResolverException(String message) {
        super(message);
    }

    public ResolverException(String message, Throwable cause) {
        super(message, cause);
    }

    public ResolverException(Throwable cause) {
        super(cause);
    }

    public static ResolverException io(String what, IOException e) {
        return new ResolverException("I/O error while " + what + ": " + e.getMessage(), e);
    }
}
