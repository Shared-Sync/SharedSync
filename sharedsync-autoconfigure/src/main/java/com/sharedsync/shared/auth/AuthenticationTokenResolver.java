package com.sharedsync.shared.auth;
/**
 * Abstraction for frameworks that need to validate an authentication token and derive
 * a principal identifier without tying consumers to a specific token technology.
 */
public interface AuthenticationTokenResolver {

    boolean validate(String token);

    String extractPrincipalId(String token);
}