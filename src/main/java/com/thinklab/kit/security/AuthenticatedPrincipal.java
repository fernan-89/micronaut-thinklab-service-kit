package com.thinklab.kit.security;

/** The identity carried by a verified access token. */
public record AuthenticatedPrincipal(String subject, String tenantId, Role role) {
}
