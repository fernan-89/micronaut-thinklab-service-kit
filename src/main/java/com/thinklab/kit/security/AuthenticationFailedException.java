package com.thinklab.kit.security;

/** A token was missing, malformed, forged, expired or otherwise unacceptable. The message is safe to log, not to expose. */
public class AuthenticationFailedException extends RuntimeException {

    public AuthenticationFailedException(String message) {
        super(message);
    }

    public AuthenticationFailedException(String message, Throwable cause) {
        super(message, cause);
    }
}
