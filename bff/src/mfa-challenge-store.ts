import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';

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

interface SealedValue {
  version: 1;
  iv: string;
  ciphertext: string;
  tag: string;
}

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
  constructor(
    private readonly redis: RedisMfaCommands,
    private readonly encryptionKey: Buffer,
    private readonly maximumTtlSeconds: number,
    private readonly now: () => number = Date.now,
  ) {
    if (encryptionKey.length !== 32) throw new Error('MFA challenge key must be exactly 32 bytes');
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
      const challenge = this.open(id, raw);
      if (!valid(challenge) || challenge.expiresAt <= this.now()) {
        await this.delete(id);
        return undefined;
      }
      return challenge;
    } catch {
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
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.encryptionKey, iv);
    cipher.setAAD(Buffer.from(`argus-bff-mfa:v1:${id}`));
    const ciphertext = Buffer.concat([cipher.update(JSON.stringify(challenge), 'utf8'), cipher.final()]);
    const sealed: SealedValue = {
      version: 1,
      iv: iv.toString('base64url'),
      ciphertext: ciphertext.toString('base64url'),
      tag: cipher.getAuthTag().toString('base64url'),
    };
    return JSON.stringify(sealed);
  }

  private open(id: string, raw: string): unknown {
    const sealed = JSON.parse(raw) as Partial<SealedValue>;
    if (sealed.version !== 1 || typeof sealed.iv !== 'string'
      || typeof sealed.ciphertext !== 'string' || typeof sealed.tag !== 'string') {
      throw new Error('Invalid MFA challenge envelope');
    }
    const decipher = createDecipheriv('aes-256-gcm', this.encryptionKey, Buffer.from(sealed.iv, 'base64url'));
    decipher.setAAD(Buffer.from(`argus-bff-mfa:v1:${id}`));
    decipher.setAuthTag(Buffer.from(sealed.tag, 'base64url'));
    return JSON.parse(Buffer.concat([
      decipher.update(Buffer.from(sealed.ciphertext, 'base64url')),
      decipher.final(),
    ]).toString('utf8')) as unknown;
  }
}
