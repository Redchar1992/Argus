import { createCipheriv, createDecipheriv, createHash, randomBytes } from 'node:crypto';
import type { AuthUser, ServerSession, SessionRepository } from './session-store.js';

const SESSION_ID_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const PREFIX = 'argus:bff:session:';

export interface RedisSessionCommands {
  get(key: string): Promise<string | null>;
  setex(key: string, seconds: number, value: string): Promise<unknown>;
  del(key: string): Promise<number>;
  sadd(key: string, member: string): Promise<number>;
  srem(key: string, member: string): Promise<number>;
  smembers(key: string): Promise<string[]>;
  expire(key: string, seconds: number): Promise<number>;
}

interface EncryptedToken {
  iv: string;
  ciphertext: string;
  tag: string;
}

interface StoredSession {
  version: 1;
  user: AuthUser;
  expiresAt: number;
  token: EncryptedToken;
}

function validUser(value: unknown): value is AuthUser {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.username === 'string' &&
    candidate.username.length > 0 &&
    candidate.username.length <= 64 &&
    (candidate.role === 'ANALYST' || candidate.role === 'ADMIN')
  );
}

function validStoredSession(value: unknown): value is StoredSession {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  const token = candidate.token as Record<string, unknown> | undefined;
  return (
    candidate.version === 1 &&
    validUser(candidate.user) &&
    typeof candidate.expiresAt === 'number' &&
    Number.isFinite(candidate.expiresAt) &&
    Boolean(token) &&
    typeof token?.iv === 'string' &&
    typeof token.ciphertext === 'string' &&
    typeof token.tag === 'string'
  );
}

/**
 * Shared session store for horizontally scaled BFF instances.
 *
 * The upstream bearer token is encrypted with AES-256-GCM before it is placed
 * in Redis. The random session id is authenticated as additional data, so a
 * ciphertext copied to another Redis key fails closed during decryption.
 */
export class RedisSessionStore implements SessionRepository {
  constructor(
    private readonly redis: RedisSessionCommands,
    private readonly encryptionKey: Buffer,
    private readonly maximumTtlSeconds: number,
    private readonly now: () => number = Date.now,
  ) {
    if (encryptionKey.length !== 32) throw new Error('Session encryption key must be exactly 32 bytes');
  }

  async create(accessToken: string, user: AuthUser, upstreamTtlSeconds: number): Promise<ServerSession> {
    if (!accessToken || accessToken.length > 16_384) throw new Error('accessToken must be a non-empty bounded string');
    if (!validUser(user)) throw new Error('user is invalid');
    if (!Number.isFinite(upstreamTtlSeconds) || upstreamTtlSeconds <= 0) {
      throw new Error('upstreamTtlSeconds must be a positive finite number');
    }

    const ttlSeconds = Math.min(upstreamTtlSeconds, this.maximumTtlSeconds);
    const redisTtlSeconds = Math.max(1, Math.ceil(ttlSeconds));
    const id = randomBytes(32).toString('base64url');
    const session: ServerSession = {
      id,
      accessToken,
      user,
      expiresAt: this.now() + ttlSeconds * 1_000,
    };
    const stored: StoredSession = {
      version: 1,
      user,
      expiresAt: session.expiresAt,
      token: this.encrypt(accessToken, id),
    };
    await this.redis.setex(this.key(id), redisTtlSeconds, JSON.stringify(stored));
    const indexKey = this.userIndexKey(user.username);
    await this.redis.sadd(indexKey, id);
    // The index must never expire before a longer-lived Session created earlier.
    await this.redis.expire(indexKey, Math.max(1, Math.ceil(this.maximumTtlSeconds)));
    return session;
  }

  async get(id: string | undefined): Promise<ServerSession | undefined> {
    if (!id || !SESSION_ID_PATTERN.test(id)) return undefined;
    const raw = await this.redis.get(this.key(id));
    if (!raw) return undefined;

    try {
      const stored: unknown = JSON.parse(raw);
      if (!validStoredSession(stored) || stored.expiresAt <= this.now()) {
        await this.redis.del(this.key(id));
        if (validStoredSession(stored)) await this.redis.srem(this.userIndexKey(stored.user.username), id);
        return undefined;
      }
      return {
        id,
        accessToken: this.decrypt(stored.token, id),
        user: stored.user,
        expiresAt: stored.expiresAt,
      };
    } catch {
      // Corrupt or tampered records are never treated as authenticated.
      await this.redis.del(this.key(id));
      return undefined;
    }
  }

  async delete(id: string | undefined): Promise<void> {
    if (!id || !SESSION_ID_PATTERN.test(id)) return;
    const raw = await this.redis.get(this.key(id));
    await this.redis.del(this.key(id));
    if (!raw) return;
    try {
      const stored: unknown = JSON.parse(raw);
      if (validStoredSession(stored)) await this.redis.srem(this.userIndexKey(stored.user.username), id);
    } catch {
      // A corrupt record has already been deleted; its bounded index expires with the Session TTL.
    }
  }

  async deleteForUser(username: string): Promise<void> {
    if (!username || username.length > 64) return;
    const indexKey = this.userIndexKey(username);
    const ids = (await this.redis.smembers(indexKey)).filter((id) => SESSION_ID_PATTERN.test(id));
    await Promise.all(ids.map((id) => this.redis.del(this.key(id))));
    await this.redis.del(indexKey);
  }

  private key(id: string): string {
    return `${PREFIX}${id}`;
  }

  private userIndexKey(username: string): string {
    const digest = createHash('sha256').update(username, 'utf8').digest('hex');
    return `argus:bff:user-sessions:${digest}`;
  }

  private encrypt(accessToken: string, sessionId: string): EncryptedToken {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.encryptionKey, iv);
    cipher.setAAD(Buffer.from(`argus-bff-session:v1:${sessionId}`));
    const ciphertext = Buffer.concat([cipher.update(accessToken, 'utf8'), cipher.final()]);
    return {
      iv: iv.toString('base64url'),
      ciphertext: ciphertext.toString('base64url'),
      tag: cipher.getAuthTag().toString('base64url'),
    };
  }

  private decrypt(token: EncryptedToken, sessionId: string): string {
    const decipher = createDecipheriv('aes-256-gcm', this.encryptionKey, Buffer.from(token.iv, 'base64url'));
    decipher.setAAD(Buffer.from(`argus-bff-session:v1:${sessionId}`));
    decipher.setAuthTag(Buffer.from(token.tag, 'base64url'));
    return Buffer.concat([
      decipher.update(Buffer.from(token.ciphertext, 'base64url')),
      decipher.final(),
    ]).toString('utf8');
  }
}
