import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';

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

interface SealedValue {
  version: 1;
  iv: string;
  ciphertext: string;
  tag: string;
}

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
  constructor(private readonly key: Buffer) {
    if (key.length !== 32) throw new Error('WebAuthn ceremony key must be exactly 32 bytes');
  }

  seal(id: string, ceremony: WebAuthnCeremony): string {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.key, iv);
    cipher.setAAD(Buffer.from(`argus-bff-webauthn:v1:${id}`));
    const ciphertext = Buffer.concat([cipher.update(JSON.stringify(ceremony), 'utf8'), cipher.final()]);
    return JSON.stringify({
      version: 1,
      iv: iv.toString('base64url'),
      ciphertext: ciphertext.toString('base64url'),
      tag: cipher.getAuthTag().toString('base64url'),
    } satisfies SealedValue);
  }

  open(id: string, raw: string): unknown {
    const sealed = JSON.parse(raw) as Partial<SealedValue>;
    if (sealed.version !== 1 || typeof sealed.iv !== 'string'
      || typeof sealed.ciphertext !== 'string' || typeof sealed.tag !== 'string') {
      throw new Error('Invalid WebAuthn ceremony envelope');
    }
    const decipher = createDecipheriv('aes-256-gcm', this.key, Buffer.from(sealed.iv, 'base64url'));
    decipher.setAAD(Buffer.from(`argus-bff-webauthn:v1:${id}`));
    decipher.setAuthTag(Buffer.from(sealed.tag, 'base64url'));
    return JSON.parse(Buffer.concat([
      decipher.update(Buffer.from(sealed.ciphertext, 'base64url')),
      decipher.final(),
    ]).toString('utf8')) as unknown;
  }
}

/** Process-local encrypted, single-use ceremony storage for development and tests. */
export class MemoryWebAuthnCeremonyStore implements WebAuthnCeremonyRepository {
  private readonly values = new Map<string, string>();
  private readonly cipher: CeremonyCipher;

  constructor(
    encryptionKey: Buffer = randomBytes(32),
    private readonly now: () => number = Date.now,
  ) {
    this.cipher = new CeremonyCipher(encryptionKey);
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
    encryptionKey: Buffer,
    private readonly maximumTtlSeconds: number,
    private readonly now: () => number = Date.now,
  ) {
    this.cipher = new CeremonyCipher(encryptionKey);
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
      return undefined;
    }
  }
}
