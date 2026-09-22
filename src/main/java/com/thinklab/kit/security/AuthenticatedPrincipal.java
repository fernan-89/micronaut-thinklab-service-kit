package com.thinklab.kit.security;

/**
 * The identity carried by a verified access token. {@code sessionId} ties the token to a login session so the
 * whole session can be revoked; it is null for tokens that are not session bound (service tokens).
 */
public record AuthenticatedPrincipal(String subject, String tenantId, Role role, String sessionId) {
}
