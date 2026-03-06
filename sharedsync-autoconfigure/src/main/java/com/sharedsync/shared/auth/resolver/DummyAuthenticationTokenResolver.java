package com.sharedsync.shared.auth.resolver;

import com.sharedsync.shared.auth.AuthenticationTokenResolver;

public class DummyAuthenticationTokenResolver implements AuthenticationTokenResolver {

    @Override
    public boolean validate(String token) { return true; }

    @Override
    public String extractPrincipalId(String token) { return "0"; }
}
