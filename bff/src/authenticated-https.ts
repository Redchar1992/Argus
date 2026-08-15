import { readFileSync, statSync } from 'node:fs';
import { Agent, request as httpsRequest } from 'node:https';
import type { AppConfig } from './config.js';

const MAX_AUTH_RESPONSE_BYTES = 1024 * 1024;

export interface TransportResponse {
  status: number;
  ok: boolean;
  text(): Promise<string>;
}

/** HTTPS transport that validates the auth-service certificate and presents a BFF client certificate. */
export class AuthenticatedHttpsTransport {
  private readonly agent: Agent;

  constructor(config: AppConfig) {
    if (!config.authMtlsEnabled || !config.authTlsCaFile || !config.authTlsCertFile || !config.authTlsKeyFile) {
      throw new Error('Authenticated TLS configuration is incomplete');
    }
    if ((statSync(config.authTlsKeyFile).mode & 0o077) !== 0) {
      throw new Error('BFF auth TLS private key must not be readable by group or other users');
    }
    this.agent = new Agent({
      ca: readFileSync(config.authTlsCaFile),
      cert: readFileSync(config.authTlsCertFile),
      key: readFileSync(config.authTlsKeyFile),
      servername: config.authTlsServerName,
      rejectUnauthorized: true,
      minVersion: 'TLSv1.2',
      maxCachedSessions: 100,
      keepAlive: true,
    });
  }

  request(url: string, init: RequestInit, timeoutMs: number): Promise<TransportResponse> {
    return new Promise((resolve, reject) => {
      const signal = AbortSignal.timeout(timeoutMs);
      const request = httpsRequest(url, {
        method: init.method,
        headers: Object.fromEntries(new Headers(init.headers).entries()),
        agent: this.agent,
        signal,
      }, (response) => {
        const chunks: Buffer[] = [];
        let size = 0;
        response.on('data', (chunk: Buffer) => {
          size += chunk.length;
          if (size > MAX_AUTH_RESPONSE_BYTES) {
            response.destroy(new Error('Auth service response exceeded the size limit'));
            return;
          }
          chunks.push(chunk);
        });
        response.on('end', () => {
          const status = response.statusCode ?? 502;
          const body = Buffer.concat(chunks).toString('utf8');
          resolve({ status, ok: status >= 200 && status < 300, text: async () => body });
        });
        response.on('error', reject);
      });
      request.on('error', reject);
      if (init.body !== undefined && init.body !== null) {
        if (typeof init.body !== 'string') {
          request.destroy(new Error('Authenticated transport accepts string request bodies only'));
          return;
        }
        request.write(init.body);
      }
      request.end();
    });
  }

  close(): void {
    this.agent.destroy();
  }
}
