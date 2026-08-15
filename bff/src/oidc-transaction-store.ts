import { randomBytes } from 'node:crypto';
import type { OidcTransaction } from './oidc.js';
import { EncryptionKeyRing, keyRing, type KeyedAesGcmEnvelope } from './encryption-keyring.js';

const TRANSACTION_ID_PATTERN = /^[A-Za-z0-9_-]{43}$/;
const VALUE_PATTERN = /^[A-Za-z0-9._~-]{32,256}$/;
const PREFIX = 'argus:bff:oidc:';

export interface OidcTransactionRepository {
  create(transaction: OidcTransaction): Promise<string>;
  consume(id: string | undefined): Promise<OidcTransaction | undefined>;
}

export interface RedisOidcCommands {
  setex(key: string, seconds: number, value: string): Promise<unknown>;
  getdel(key: string): Promise<string | null>;
}

interface SealedValue extends KeyedAesGcmEnvelope { version: 1 }

function validTransaction(value: unknown): value is OidcTransaction {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Record<string, unknown>;
  return (
    typeof candidate.state === 'string' &&
    VALUE_PATTERN.test(candidate.state) &&
    typeof candidate.nonce === 'string' &&
    VALUE_PATTERN.test(candidate.nonce) &&
    typeof candidate.codeVerifier === 'string' &&
    VALUE_PATTERN.test(candidate.codeVerifier) &&
    typeof candidate.expiresAt === 'number' &&
    Number.isFinite(candidate.expiresAt)
  );
}

export class MemoryOidcTransactionStore implements OidcTransactionRepository {
  private readonly transactions = new Map<string, OidcTransaction>();

  constructor(private readonly now: () => number = Date.now) {}

  async create(transaction: OidcTransaction): Promise<string> {
    if (!validTransaction(transaction) || transaction.expiresAt <= this.now()) {
      throw new Error('OIDC transaction is invalid');
    }
    const id = randomBytes(32).toString('base64url');
    this.transactions.set(id, transaction);
    return id;
  }

  async consume(id: string | undefined): Promise<OidcTransaction | undefined> {
    if (!id || !TRANSACTION_ID_PATTERN.test(id)) return undefined;
    const transaction = this.transactions.get(id);
    this.transactions.delete(id);
    if (!transaction || transaction.expiresAt <= this.now()) return undefined;
    return transaction;
  }
}

/** Redis-backed, encrypted, one-time OIDC state/nonce/PKCE transaction store. */
export class RedisOidcTransactionStore implements OidcTransactionRepository {
  private readonly encryption: EncryptionKeyRing;

  constructor(
    private readonly redis: RedisOidcCommands,
    encryption: Buffer | EncryptionKeyRing,
    private readonly ttlSeconds: number,
    private readonly now: () => number = Date.now,
  ) {
    this.encryption = keyRing(encryption);
  }

  async create(transaction: OidcTransaction): Promise<string> {
    if (!validTransaction(transaction) || transaction.expiresAt <= this.now()) {
      throw new Error('OIDC transaction is invalid');
    }
    const id = randomBytes(32).toString('base64url');
    await this.redis.setex(this.key(id), this.ttlSeconds, this.seal(id, transaction));
    return id;
  }

  async consume(id: string | undefined): Promise<OidcTransaction | undefined> {
    if (!id || !TRANSACTION_ID_PATTERN.test(id)) return undefined;
    const raw = await this.redis.getdel(this.key(id));
    if (!raw) return undefined;
    try {
      const transaction = this.open(id, raw);
      if (!validTransaction(transaction) || transaction.expiresAt <= this.now()) return undefined;
      return transaction;
    } catch {
      return undefined;
    }
  }

  private key(id: string): string {
    return `${PREFIX}${id}`;
  }

  private seal(id: string, transaction: OidcTransaction): string {
    const value: SealedValue = {
      version: 1,
      ...this.encryption.seal('argus-bff-oidc:v1', id, JSON.stringify(transaction)),
    };
    return JSON.stringify(value);
  }

  private open(id: string, raw: string): unknown {
    const sealed = JSON.parse(raw) as Partial<SealedValue>;
    if (
      sealed.version !== 1 ||
      typeof sealed.iv !== 'string' ||
      typeof sealed.ciphertext !== 'string' ||
      typeof sealed.tag !== 'string' ||
      (sealed.kid !== undefined && typeof sealed.kid !== 'string')
    ) {
      throw new Error('Invalid OIDC transaction envelope');
    }
    return JSON.parse(this.encryption.open('argus-bff-oidc:v1', id, sealed as SealedValue).plaintext) as unknown;
  }
}
