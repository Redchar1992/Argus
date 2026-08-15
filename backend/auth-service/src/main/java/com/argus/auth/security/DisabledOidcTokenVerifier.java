package com.argus.auth.security;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Component
@ConditionalOnProperty(name = "argus.oidc.enabled", havingValue = "false", matchIfMissing = true)
public class DisabledOidcTokenVerifier implements OidcTokenVerifier {

    @Override
    public OidcIdentity verify(String idToken, String expectedNonce) {
        throw new ResponseStatusException(NOT_FOUND, "OIDC login is not enabled");
    }
}
