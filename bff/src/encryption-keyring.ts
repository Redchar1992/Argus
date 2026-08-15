import { createCipheriv, createDecipheriv, randomBytes } from 'node:crypto';

const KEY_ID_PATTERN = /^[A-Za-z0-9_-]{1,32}$/;

export interface KeyedAesGcmEnvelope {
  kid?: string;
  iv: string;
  ciphertext: string;
  tag: string;
}

export interface OpenedValue {
  plaintext: string;
  keyId: string;
}

/** Versioned in-process key ring: new writes use primary; retained keys keep old records readable. */
export class EncryptionKeyRing {
  readonly primaryKeyId: string;
  private readonly keys: ReadonlyMap<string, Buffer>;

  constructor(primaryKeyId: string, keys: ReadonlyMap<string, Buffer>) {
    if (!KEY_ID_PATTERN.test(primaryKeyId) || !keys.has(primaryKeyId)) {
      throw new Error('Encryption primary key id must reference a configured key');
    }
    if (keys.size === 0 || keys.size > 8) throw new Error('Encryption key ring must contain between 1 and 8 keys');
    const copied = new Map<string, Buffer>();
    for (const [keyId, key] of keys) {
      if (!KEY_ID_PATTERN.test(keyId) || copied.has(keyId) || key.length !== 32) {
        throw new Error('Encryption key ring contains an invalid key id or key');
      }
      copied.set(keyId, Buffer.from(key));
    }
    this.primaryKeyId = primaryKeyId;
    this.keys = copied;
  }

  seal(purpose: string, recordId: string, plaintext: string): KeyedAesGcmEnvelope {
    const iv = randomBytes(12);
    const cipher = createCipheriv('aes-256-gcm', this.keys.get(this.primaryKeyId)!, iv);
    cipher.setAAD(Buffer.from(`${purpose}:${recordId}`));
    const ciphertext = Buffer.concat([cipher.update(plaintext, 'utf8'), cipher.final()]);
    return {
      kid: this.primaryKeyId,
      iv: iv.toString('base64url'),
      ciphertext: ciphertext.toString('base64url'),
      tag: cipher.getAuthTag().toString('base64url'),
    };
  }

  open(purpose: string, recordId: string, envelope: KeyedAesGcmEnvelope): OpenedValue {
    const candidates = envelope.kid
      ? [[envelope.kid, this.keys.get(envelope.kid)] as const]
      : [...this.keys.entries()];
    for (const [keyId, key] of candidates) {
      if (!key) continue;
      try {
        const decipher = createDecipheriv('aes-256-gcm', key, Buffer.from(envelope.iv, 'base64url'));
        decipher.setAAD(Buffer.from(`${purpose}:${recordId}`));
        decipher.setAuthTag(Buffer.from(envelope.tag, 'base64url'));
        return {
          plaintext: Buffer.concat([
            decipher.update(Buffer.from(envelope.ciphertext, 'base64url')),
            decipher.final(),
          ]).toString('utf8'),
          keyId,
        };
      } catch {
        // Legacy envelopes have no kid, so try each retained key without revealing which failed.
      }
    }
    throw new Error('Encrypted value could not be opened with the configured key ring');
  }

  needsRotation(envelope: KeyedAesGcmEnvelope, openedWith: string): boolean {
    return envelope.kid !== this.primaryKeyId || openedWith !== this.primaryKeyId;
  }
}

export function keyRing(value: Buffer | EncryptionKeyRing): EncryptionKeyRing {
  return value instanceof EncryptionKeyRing
    ? value
    : new EncryptionKeyRing('legacy-v1', new Map([['legacy-v1', value]]));
}
