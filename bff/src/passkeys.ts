import {
  generateAuthenticationOptions,
  generateRegistrationOptions,
  verifyAuthenticationResponse,
  verifyRegistrationResponse,
  type AuthenticationResponseJSON,
  type AuthenticatorTransportFuture,
  type PublicKeyCredentialCreationOptionsJSON,
  type PublicKeyCredentialRequestOptionsJSON,
  type RegistrationResponseJSON,
} from '@simplewebauthn/server';
import type { AppConfig } from './config.js';

const CREDENTIAL_ID_PATTERN = /^[A-Za-z0-9_-]{16,2048}$/;

export interface PasskeyMaterial {
  credentialId: string;
  publicKey: string;
  counter: number;
  transports: string[];
  username: string;
  deviceType: 'singleDevice' | 'multiDevice';
  backedUp: boolean;
}

export interface PasskeyRegistrationContext {
  userId: number;
  username: string;
  credentials: PasskeyMaterial[];
}

export interface PasskeyView {
  credentialId: string;
  label: string;
  transports: string[];
  deviceType: 'singleDevice' | 'multiDevice';
  backedUp: boolean;
  createdAt: string;
  lastUsedAt: string | null;
}

export interface VerifiedPasskeyRegistration {
  credentialId: string;
  publicKey: string;
  counter: number;
  transports: string[];
  deviceType: 'singleDevice' | 'multiDevice';
  backedUp: boolean;
  aaguid: string;
}

export interface VerifiedPasskeyAuthentication {
  newCounter: number;
  deviceType: 'singleDevice' | 'multiDevice';
  backedUp: boolean;
}

export interface PasskeyCeremonies {
  registrationOptions(context: PasskeyRegistrationContext): Promise<PublicKeyCredentialCreationOptionsJSON>;
  verifyRegistration(response: unknown, expectedChallenge: string): Promise<VerifiedPasskeyRegistration>;
  authenticationOptions(): Promise<PublicKeyCredentialRequestOptionsJSON>;
  credentialId(response: unknown): string;
  verifyAuthentication(
    response: unknown,
    expectedChallenge: string,
    material: PasskeyMaterial,
  ): Promise<VerifiedPasskeyAuthentication>;
}

export class SimpleWebAuthnPasskeys implements PasskeyCeremonies {
  constructor(private readonly config: AppConfig) {}

  async registrationOptions(
    context: PasskeyRegistrationContext,
  ): Promise<PublicKeyCredentialCreationOptionsJSON> {
    return generateRegistrationOptions({
      rpName: this.config.webauthnRpName,
      rpID: this.config.webauthnRpId,
      userName: context.username,
      userDisplayName: context.username,
      userID: new TextEncoder().encode(`argus-user:${context.userId}`),
      timeout: this.config.webauthnCeremonyTtlSeconds * 1_000,
      attestationType: 'none',
      authenticatorSelection: {
        residentKey: 'required',
        requireResidentKey: true,
        userVerification: 'required',
      },
      excludeCredentials: context.credentials.map((credential) => ({
        id: credential.credentialId,
        transports: credential.transports as AuthenticatorTransportFuture[],
      })),
    });
  }

  async verifyRegistration(response: unknown, expectedChallenge: string): Promise<VerifiedPasskeyRegistration> {
    const verification = await verifyRegistrationResponse({
      response: response as RegistrationResponseJSON,
      expectedChallenge,
      expectedOrigin: this.config.webauthnOrigin,
      expectedRPID: this.config.webauthnRpId,
      requireUserPresence: true,
      requireUserVerification: true,
    });
    if (!verification.verified) throw new Error('Passkey registration was not verified');
    const { registrationInfo } = verification;
    return {
      credentialId: registrationInfo.credential.id,
      publicKey: Buffer.from(registrationInfo.credential.publicKey).toString('base64url'),
      counter: registrationInfo.credential.counter,
      transports: [...(registrationInfo.credential.transports ?? [])],
      deviceType: registrationInfo.credentialDeviceType,
      backedUp: registrationInfo.credentialBackedUp,
      aaguid: registrationInfo.aaguid,
    };
  }

  async authenticationOptions(): Promise<PublicKeyCredentialRequestOptionsJSON> {
    return generateAuthenticationOptions({
      rpID: this.config.webauthnRpId,
      timeout: this.config.webauthnCeremonyTtlSeconds * 1_000,
      userVerification: 'required',
      // Intentionally omit allowCredentials: resident credentials enable usernameless sign-in.
    });
  }

  credentialId(response: unknown): string {
    if (!response || typeof response !== 'object') throw new Error('Invalid passkey assertion');
    const id = (response as Record<string, unknown>).id;
    if (typeof id !== 'string' || !CREDENTIAL_ID_PATTERN.test(id)) throw new Error('Invalid passkey assertion');
    return id;
  }

  async verifyAuthentication(
    response: unknown,
    expectedChallenge: string,
    material: PasskeyMaterial,
  ): Promise<VerifiedPasskeyAuthentication> {
    const verification = await verifyAuthenticationResponse({
      response: response as AuthenticationResponseJSON,
      expectedChallenge,
      expectedOrigin: this.config.webauthnOrigin,
      expectedRPID: this.config.webauthnRpId,
      credential: {
        id: material.credentialId,
        publicKey: Uint8Array.from(Buffer.from(material.publicKey, 'base64url')),
        counter: material.counter,
        transports: material.transports as AuthenticatorTransportFuture[],
      },
      requireUserVerification: true,
    });
    if (!verification.verified || !verification.authenticationInfo.userVerified) {
      throw new Error('Passkey authentication was not verified');
    }
    return {
      newCounter: verification.authenticationInfo.newCounter,
      deviceType: verification.authenticationInfo.credentialDeviceType,
      backedUp: verification.authenticationInfo.credentialBackedUp,
    };
  }
}
