import { randomBytes } from 'node:crypto';
import { EncryptionKeyRing, keyRing, type KeyedAesGcmEnvelope } from './encryption-keyring.js';
import type { EncryptedStoreObserver } from './metrics.js';

const ID_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const CHALLENGE_PATTERN = /^[A-Za-z0-9_-]{32,256}$/;
const PREFIX = 'argus:bff:webauthn:';

export type WebAuthnCeremony =
  | { kind: 'registration'; challenge: string; username: string; expiresAt: number }
  | { kind: 'authentication'; challenge: string; expiresAt: number };

export interface WebAuthnCeremonyRepository {
  create(ceremony: WebAuthnCeremony): Promise<string>;
  consume(id: string | undefined): Promise<WebAuthnCeremony | undefined>;
}

export interface RedisWebAuthnCommands {
  setex(key: string, seconds: number, value: string): Promise<unknown>;
  getdel(key: string): Promise<string | null>;
}

interface SealedValue extends KeyedAesGcmEnvelope { version: 1 }

function valid(value: unknown): value is WebAuthnCeremony {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  const common = CHALLENGE_PATTERN.test(String(candidate.challenge ?? ''))
    && typeof candidate.expiresAt === 'number'
    && Number.isFinite(candidate.expiresAt);
  if (!common) return false;
  if (candidate.kind === 'authentication') return true;
  return candidate.kind === 'registration'
    && typeof candidate.username === 'string'
    && candidate.username.length > 0
    && candidate.username.length <= 64;
}

class CeremonyCipher {
  private readonly encryption: EncryptionKeyRing;

  constructor(encryption: Buffer | EncryptionKeyRing) {
    this.encryption = keyRing(encryption);
  }

  seal(id: string, ceremony: WebAuthnCeremony): string {
    return JSON.stringify({
      version: 1,
      ...this.encryption.seal('argus-bff-webauthn:v1', id, JSON.stringify(ceremony)),
    } satisfies SealedValue);
  }

  open(id: string, raw: string): unknown {
    const sealed = JSON.parse(raw) as Partial<SealedValue>;
    if (sealed.version !== 1 || typeof sealed.iv !== 'string'
      || typeof sealed.ciphertext !== 'string' || typeof sealed.tag !== 'string'
      || (sealed.kid !== undefined && typeof sealed.kid !== 'string')) {
      throw new Error('Invalid WebAuthn ceremony envelope');
    }
    return JSON.parse(
      this.encryption.open('argus-bff-webauthn:v1', id, sealed as SealedValue).plaintext,
    ) as unknown;
  }
}

/** Process-local encrypted, single-use ceremony storage for development and tests. */
export class MemoryWebAuthnCeremonyStore implements WebAuthnCeremonyRepository {
  private readonly values = new Map<string, string>();
  private readonly cipher: CeremonyCipher;

  constructor(
    encryption: Buffer | EncryptionKeyRing = randomBytes(32),
    private readonly now: () => number = Date.now,
  ) {
    this.cipher = new CeremonyCipher(encryption);
  }

  async create(ceremony: WebAuthnCeremony): Promise<string> {
    if (!valid(ceremony) || ceremony.expiresAt <= this.now()) throw new Error('WebAuthn ceremony is invalid');
    const id = randomBytes(32).toString('base64url');
    this.values.set(id, this.cipher.seal(id, ceremony));
    return id;
  }

  async consume(id: string | undefined): Promise<WebAuthnCeremony | undefined> {
    if (!id || !ID_PATTERN.test(id)) return undefined;
    const raw = this.values.get(id);
    this.values.delete(id);
    if (!raw) return undefined;
    try {
      const ceremony = this.cipher.open(id, raw);
      return valid(ceremony) && ceremony.expiresAt > this.now() ? ceremony : undefined;
    } catch {
      return undefined;
    }
  }
}

/** Shared encrypted, single-use ceremony storage for horizontally scaled BFF instances. */
export class RedisWebAuthnCeremonyStore implements WebAuthnCeremonyRepository {
  private readonly cipher: CeremonyCipher;

  constructor(
    private readonly redis: RedisWebAuthnCommands,
    encryption: Buffer | EncryptionKeyRing,
    private readonly maximumTtlSeconds: number,
    private readonly now: () => number = Date.now,
    private readonly observer?: EncryptedStoreObserver,
  ) {
    this.cipher = new CeremonyCipher(encryption);
  }

  async create(ceremony: WebAuthnCeremony): Promise<string> {
    if (!valid(ceremony) || ceremony.expiresAt <= this.now()) throw new Error('WebAuthn ceremony is invalid');
    const id = randomBytes(32).toString('base64url');
    const remaining = Math.max(1, Math.ceil((ceremony.expiresAt - this.now()) / 1_000));
    await this.redis.setex(
      `${PREFIX}${id}`,
      Math.min(this.maximumTtlSeconds, remaining),
      this.cipher.seal(id, ceremony),
    );
    return id;
  }

  async consume(id: string | undefined): Promise<WebAuthnCeremony | undefined> {
    if (!id || !ID_PATTERN.test(id)) return undefined;
    const raw = await this.redis.getdel(`${PREFIX}${id}`);
    if (!raw) return undefined;
    try {
      const ceremony = this.cipher.open(id, raw);
      return valid(ceremony) && ceremony.expiresAt > this.now() ? ceremony : undefined;
    } catch {
      this.observer?.recordRejectedRecord('webauthn');
      return undefined;
    }
  }
}
