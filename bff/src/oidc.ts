import * as client from 'openid-client';
import type { AppConfig } from './config.js';

export interface OidcAuthorizationRequest {
  redirectUrl: string;
  state: string;
  nonce: string;
  codeVerifier: string;
  expiresAt: number;
}

export interface OidcTransaction {
  state: string;
  nonce: string;
  codeVerifier: string;
  expiresAt: number;
}

export interface OidcRelyingParty {
  begin(): Promise<OidcAuthorizationRequest>;
  complete(callbackUrl: URL, transaction: OidcTransaction): Promise<{ idToken: string }>;
}

/**
 * Standards-based OIDC Authorization Code + PKCE client.
 *
 * Discovery and provider metadata validation happen during process startup.
 * Every browser redirect gets independent state, nonce, and PKCE material.
 */
export class OpenIdClientRelyingParty implements OidcRelyingParty {
  private constructor(
    private readonly configuration: client.Configuration,
    private readonly redirectUri: string,
    private readonly scopes: string,
    private readonly now: () => number,
    private readonly transactionTtlSeconds: number,
  ) {}

  static async discover(config: AppConfig): Promise<OpenIdClientRelyingParty> {
    if (!config.oidcIssuer || !config.oidcClientId || !config.oidcRedirectUri) {
      throw new Error('OIDC configuration is incomplete');
    }
    const metadata: Partial<client.ClientMetadata> | string = config.oidcClientSecret
      ? config.oidcClientSecret
      : { token_endpoint_auth_method: 'none' };
    const configuration = await client.discovery(
      new URL(config.oidcIssuer),
      config.oidcClientId,
      metadata,
    );
    return new OpenIdClientRelyingParty(
      configuration,
      config.oidcRedirectUri,
      config.oidcScopes,
      Date.now,
      config.oidcTransactionTtlSeconds,
    );
  }

  async begin(): Promise<OidcAuthorizationRequest> {
    const codeVerifier = client.randomPKCECodeVerifier();
    const codeChallenge = await client.calculatePKCECodeChallenge(codeVerifier);
    const state = client.randomState();
    const nonce = client.randomNonce();
    const redirectUrl = client.buildAuthorizationUrl(this.configuration, {
      redirect_uri: this.redirectUri,
      scope: this.scopes,
      response_type: 'code',
      code_challenge: codeChallenge,
      code_challenge_method: 'S256',
      state,
      nonce,
    });
    return {
      redirectUrl: redirectUrl.href,
      state,
      nonce,
      codeVerifier,
      expiresAt: this.now() + this.transactionTtlSeconds * 1_000,
    };
  }

  async complete(callbackUrl: URL, transaction: OidcTransaction): Promise<{ idToken: string }> {
    if (transaction.expiresAt <= this.now()) throw new Error('OIDC transaction expired');
    const tokens = await client.authorizationCodeGrant(
      this.configuration,
      callbackUrl,
      {
        pkceCodeVerifier: transaction.codeVerifier,
        expectedState: transaction.state,
        expectedNonce: transaction.nonce,
        idTokenExpected: true,
      },
    );
    if (!tokens.id_token || !tokens.claims()) {
      throw new Error('OIDC provider did not return a valid ID Token');
    }
    return { idToken: tokens.id_token };
  }
}
