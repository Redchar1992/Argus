import { randomBytes } from 'node:crypto';
import { EncryptionKeyRing, keyRing, type KeyedAesGcmEnvelope } from './encryption-keyring.js';
import type { EncryptedStoreObserver } from './metrics.js';

const ID_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const CHALLENGE_PATTERN = /^[A-Za-z0-9_-]{32,256}$/;
const PREFIX = 'argus:bff:mfa:';

export type MfaMethod = 'TOTP' | 'RECOVERY_CODE';

export interface StoredMfaChallenge {
  challengeToken: string;
  methods: MfaMethod[];
  username: string;
  expiresAt: number;
}

export interface MfaChallengeRepository {
  create(challenge: StoredMfaChallenge): Promise<string>;
  get(id: string | undefined): Promise<StoredMfaChallenge | undefined>;
  delete(id: string | undefined): Promise<void>;
}

export interface RedisMfaCommands {
  get(key: string): Promise<string | null>;
  setex(key: string, seconds: number, value: string): Promise<unknown>;
  del(key: string): Promise<unknown>;
}

interface SealedValue extends KeyedAesGcmEnvelope { version: 1 }

function valid(value: unknown): value is StoredMfaChallenge {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.challengeToken === 'string' &&
    CHALLENGE_PATTERN.test(candidate.challengeToken) &&
    typeof candidate.username === 'string' &&
    candidate.username.length > 0 &&
    candidate.username.length <= 64 &&
    Array.isArray(candidate.methods) &&
    candidate.methods.length > 0 &&
    candidate.methods.every((method) => method === 'TOTP' || method === 'RECOVERY_CODE') &&
    typeof candidate.expiresAt === 'number' &&
    Number.isFinite(candidate.expiresAt)
  );
}

export class MemoryMfaChallengeStore implements MfaChallengeRepository {
  private readonly values = new Map<string, StoredMfaChallenge>();

  constructor(private readonly now: () => number = Date.now) {}

  async create(challenge: StoredMfaChallenge): Promise<string> {
    if (!valid(challenge) || challenge.expiresAt <= this.now()) throw new Error('MFA challenge is invalid');
    const id = randomBytes(32).toString('base64url');
    this.values.set(id, challenge);
    return id;
  }

  async get(id: string | undefined): Promise<StoredMfaChallenge | undefined> {
    if (!id || !ID_PATTERN.test(id)) return undefined;
    const challenge = this.values.get(id);
    if (!challenge || challenge.expiresAt <= this.now()) {
      this.values.delete(id);
      return undefined;
    }
    return challenge;
  }

  async delete(id: string | undefined): Promise<void> {
    if (id && ID_PATTERN.test(id)) this.values.delete(id);
  }
}

/** Encrypted pre-authentication challenge storage; the Java challenge token never reaches JS. */
export class RedisMfaChallengeStore implements MfaChallengeRepository {
  private readonly encryption: EncryptionKeyRing;

  constructor(
    private readonly redis: RedisMfaCommands,
    encryption: Buffer | EncryptionKeyRing,
    private readonly maximumTtlSeconds: number,
    private readonly now: () => number = Date.now,
    private readonly observer?: EncryptedStoreObserver,
  ) {
    this.encryption = keyRing(encryption);
  }

  async create(challenge: StoredMfaChallenge): Promise<string> {
    if (!valid(challenge) || challenge.expiresAt <= this.now()) throw new Error('MFA challenge is invalid');
    const id = randomBytes(32).toString('base64url');
    const remainingSeconds = Math.max(1, Math.ceil((challenge.expiresAt - this.now()) / 1_000));
    await this.redis.setex(
      this.key(id),
      Math.min(this.maximumTtlSeconds, remainingSeconds),
      this.seal(id, challenge),
    );
    return id;
  }

  async get(id: string | undefined): Promise<StoredMfaChallenge | undefined> {
    if (!id || !ID_PATTERN.test(id)) return undefined;
    const raw = await this.redis.get(this.key(id));
    if (!raw) return undefined;
    try {
      const opened = this.open(id, raw);
      const challenge = opened.value;
      if (!valid(challenge) || challenge.expiresAt <= this.now()) {
        await this.delete(id);
        return undefined;
      }
      if (this.encryption.needsRotation(opened.envelope, opened.keyId)) {
        const remaining = Math.max(1, Math.ceil((challenge.expiresAt - this.now()) / 1_000));
        await this.redis.setex(this.key(id), Math.min(this.maximumTtlSeconds, remaining), this.seal(id, challenge));
        this.observer?.recordKeyRotation('mfa');
      }
      return challenge;
    } catch {
      this.observer?.recordRejectedRecord('mfa');
      await this.delete(id);
      return undefined;
    }
  }

  async delete(id: string | undefined): Promise<void> {
    if (id && ID_PATTERN.test(id)) await this.redis.del(this.key(id));
  }

  private key(id: string): string {
    return `${PREFIX}${id}`;
  }

  private seal(id: string, challenge: StoredMfaChallenge): string {
    const encrypted = this.encryption.seal('argus-bff-mfa:v1', id, JSON.stringify(challenge));
    const sealed: SealedValue = {
      version: 1,
      ...encrypted,
    };
    return JSON.stringify(sealed);
  }

  private open(id: string, raw: string): { value: unknown; envelope: SealedValue; keyId: string } {
    const sealed = JSON.parse(raw) as Partial<SealedValue>;
    if (sealed.version !== 1 || typeof sealed.iv !== 'string'
      || typeof sealed.ciphertext !== 'string' || typeof sealed.tag !== 'string'
      || (sealed.kid !== undefined && typeof sealed.kid !== 'string')) {
      throw new Error('Invalid MFA challenge envelope');
    }
    const envelope = sealed as SealedValue;
    const opened = this.encryption.open('argus-bff-mfa:v1', id, envelope);
    return { value: JSON.parse(opened.plaintext) as unknown, envelope, keyId: opened.keyId };
  }
}
