import { X509Certificate } from 'node:crypto';
import { readFileSync } from 'node:fs';
import {
  Counter,
  Gauge,
  Histogram,
  Registry,
  collectDefaultMetrics,
} from 'prom-client';

export type AuthFlow = 'password' | 'oidc' | 'mfa_totp' | 'mfa_recovery' | 'mfa_unknown'
  | 'recovery' | 'passkey';
export type AuthOutcome = 'authenticated' | 'mfa_required' | 'recovered' | 'rejected' | 'error';
export type EncryptedStore = 'session' | 'mfa' | 'oidc' | 'webauthn';

/** Prometheus metrics with intentionally bounded labels and no user/session identifiers. */
export class IdentityMetrics {
  readonly registry = new Registry();
  private readonly authAttempts: Counter<'flow' | 'outcome'>;
  private readonly sessionEvents: Counter<'event'>;
  private readonly httpDuration: Histogram<'method' | 'route' | 'status_class'>;
  private readonly upstreamDuration: Histogram<'service' | 'purpose' | 'outcome'>;
  private readonly dependencyUp: Gauge<'dependency'>;
  private readonly dependencyErrors: Counter<'dependency'>;
  private readonly encryptedRecordEvents: Counter<'store' | 'event'>;
  private readonly certificateExpiry: Gauge<'certificate'>;

  constructor(region: string) {
    this.registry.setDefaultLabels({ service: 'argus-identity-bff', region });
    collectDefaultMetrics({ register: this.registry, prefix: 'argus_bff_process_' });
    this.authAttempts = new Counter({
      name: 'argus_bff_auth_attempts_total',
      help: 'Authentication attempts by bounded flow and terminal outcome.',
      labelNames: ['flow', 'outcome'],
      registers: [this.registry],
    });
    this.sessionEvents = new Counter({
      name: 'argus_bff_session_events_total',
      help: 'Server-side Session lifecycle events.',
      labelNames: ['event'],
      registers: [this.registry],
    });
    this.httpDuration = new Histogram({
      name: 'argus_bff_http_request_duration_seconds',
      help: 'BFF HTTP request latency by route template, never raw URL.',
      labelNames: ['method', 'route', 'status_class'],
      buckets: [0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5],
      registers: [this.registry],
    });
    this.upstreamDuration = new Histogram({
      name: 'argus_bff_upstream_request_duration_seconds',
      help: 'Upstream latency by service, request purpose and normalized outcome.',
      labelNames: ['service', 'purpose', 'outcome'],
      buckets: [0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5],
      registers: [this.registry],
    });
    this.dependencyUp = new Gauge({
      name: 'argus_bff_dependency_up',
      help: 'Whether a required dependency is currently available (1) or unavailable (0).',
      labelNames: ['dependency'],
      registers: [this.registry],
    });
    this.dependencyErrors = new Counter({
      name: 'argus_bff_dependency_errors_total',
      help: 'Dependency errors observed by the BFF.',
      labelNames: ['dependency'],
      registers: [this.registry],
    });
    this.encryptedRecordEvents = new Counter({
      name: 'argus_bff_encrypted_record_events_total',
      help: 'Key rotation and rejected encrypted-record events by bounded store name.',
      labelNames: ['store', 'event'],
      registers: [this.registry],
    });
    this.certificateExpiry = new Gauge({
      name: 'argus_bff_tls_certificate_expiry_timestamp_seconds',
      help: 'Not-after timestamp for local workload certificates loaded by the BFF.',
      labelNames: ['certificate'],
      registers: [this.registry],
    });
  }

  recordAuth(flow: AuthFlow, outcome: AuthOutcome): void {
    this.authAttempts.inc({ flow, outcome });
  }

  recordSession(event: 'created' | 'restored' | 'missing' | 'expired' | 'deleted' | 'user_invalidated'): void {
    this.sessionEvents.inc({ event });
  }

  observeHttp(method: string, route: string, statusCode: number, seconds: number): void {
    const statusClass = `${Math.floor(statusCode / 100)}xx`;
    this.httpDuration.observe({ method, route, status_class: statusClass }, seconds);
  }

  observeUpstream(
    service: 'auth' | 'investigation',
    purpose: 'login' | 'api',
    outcome: 'success' | 'rejected' | 'timeout' | 'unavailable',
    seconds: number,
  ): void {
    this.upstreamDuration.observe({ service, purpose, outcome }, seconds);
  }

  setDependency(dependency: 'redis' | 'auth', up: boolean): void {
    this.dependencyUp.set({ dependency }, up ? 1 : 0);
  }

  recordDependencyError(dependency: 'redis' | 'auth'): void {
    this.dependencyErrors.inc({ dependency });
  }

  recordKeyRotation(store: EncryptedStore): void {
    this.encryptedRecordEvents.inc({ store, event: 'rotated' });
  }

  recordRejectedRecord(store: EncryptedStore): void {
    this.encryptedRecordEvents.inc({ store, event: 'rejected' });
  }

  observeCertificate(name: 'auth_client' | 'redis_client' | 'auth_ca' | 'redis_ca', file: string): void {
    const certificate = new X509Certificate(readFileSync(file));
    const expiry = Date.parse(certificate.validTo) / 1_000;
    if (!Number.isFinite(expiry)) throw new Error(`Unable to read ${name} certificate expiry`);
    this.certificateExpiry.set({ certificate: name }, expiry);
  }

  get contentType(): string {
    return this.registry.contentType;
  }

  async render(): Promise<string> {
    return this.registry.metrics();
  }
}

export interface EncryptedStoreObserver {
  recordKeyRotation(store: EncryptedStore): void;
  recordRejectedRecord(store: EncryptedStore): void;
}
