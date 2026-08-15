#!/usr/bin/env node

import { createHmac } from 'node:crypto';
import { fileURLToPath } from 'node:url';

const STEP_SECONDS = 30;

export function decodeBase32(value) {
  const normalized = value.replaceAll('=', '').replaceAll(/\s+/g, '').toUpperCase();
  if (!normalized || !/^[A-Z2-7]+$/.test(normalized)) {
    throw new Error('TOTP secret must use RFC 4648 Base32 (A-Z and 2-7)');
  }
  const output = [];
  let buffer = 0;
  let bits = 0;
  for (const character of normalized) {
    const decoded = character >= 'A' && character <= 'Z'
      ? character.charCodeAt(0) - 65
      : character.charCodeAt(0) - 50 + 26;
    buffer = (buffer << 5) | decoded;
    bits += 5;
    if (bits >= 8) {
      output.push((buffer >>> (bits - 8)) & 0xff);
      bits -= 8;
    }
  }
  return Buffer.from(output);
}

export function totpCode(secret, epochSeconds = Math.floor(Date.now() / 1000), offset = 0) {
  const counter = Math.floor(epochSeconds / STEP_SECONDS) + offset;
  if (!Number.isSafeInteger(counter) || counter < 0) throw new Error('Invalid TOTP counter');
  const message = Buffer.alloc(8);
  message.writeBigUInt64BE(BigInt(counter));
  const digest = createHmac('sha1', decodeBase32(secret)).update(message).digest();
  const position = digest[digest.length - 1] & 0x0f;
  const binary = ((digest[position] & 0x7f) << 24)
    | ((digest[position + 1] & 0xff) << 16)
    | ((digest[position + 2] & 0xff) << 8)
    | (digest[position + 3] & 0xff);
  return String(binary % 1_000_000).padStart(6, '0');
}

function main() {
  const secret = process.argv[2];
  if (!secret || secret.startsWith('--')) {
    console.error('Usage: node scripts/totp-code.mjs <BASE32_SECRET> [--offset=-1|0|1] [--at=EPOCH_SECONDS]');
    process.exit(2);
  }
  const offsetArgument = process.argv.find((argument) => argument.startsWith('--offset='));
  const atArgument = process.argv.find((argument) => argument.startsWith('--at='));
  const offset = Number(offsetArgument?.slice('--offset='.length) ?? '0');
  const epochSeconds = Number(atArgument?.slice('--at='.length) ?? Math.floor(Date.now() / 1000));
  if (![-1, 0, 1].includes(offset) || !Number.isSafeInteger(epochSeconds) || epochSeconds < 0) {
    console.error('Offset must be -1, 0 or 1 and --at must be a non-negative epoch second.');
    process.exit(2);
  }
  console.log(totpCode(secret, epochSeconds, offset));
  if (!atArgument) {
    const remaining = STEP_SECONDS - (epochSeconds % STEP_SECONDS);
    console.error(`Valid window: offset ${offset}; current step changes in ${remaining}s`);
  }
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  try {
    main();
  } catch (error) {
    console.error(error instanceof Error ? error.message : 'Unable to compute TOTP');
    process.exit(1);
  }
}
