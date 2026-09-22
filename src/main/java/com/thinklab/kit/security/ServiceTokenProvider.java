package com.thinklab.kit.security;

/** Supplies the bearer token this service presents when it calls another ThinkLab service. */
public interface ServiceTokenProvider {

    /** A currently valid service access token. */
    String token();
}
