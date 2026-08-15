package com.argus.auth.security;

/** Verifies an upstream provider ID token and returns its immutable identity key. */
public interface OidcTokenVerifier {

    OidcIdentity verify(String idToken, String expectedNonce);

    record OidcIdentity(String issuer, String subject, String email) {
    }
}
