import { randomBytes } from 'node:crypto';

export type Role = 'ANALYST' | 'ADMIN';

export interface AuthUser {
  username: string;
  role: Role;
}

export interface ServerSession {
  id: string;
  accessToken: string;
  user: AuthUser;
  expiresAt: number;
}

export interface SessionRepository {
  create(accessToken: string, user: AuthUser, upstreamTtlSeconds: number): Promise<ServerSession>;
  get(id: string | undefined): Promise<ServerSession | undefined>;
  delete(id: string | undefined): Promise<void>;
}

export class SessionStore implements SessionRepository {
  private readonly sessions = new Map<string, ServerSession>();

  constructor(
    private readonly maximumTtlSeconds: number,
    private readonly now: () => number = Date.now,
  ) {}

  async create(accessToken: string, user: AuthUser, upstreamTtlSeconds: number): Promise<ServerSession> {
    if (!Number.isFinite(upstreamTtlSeconds) || upstreamTtlSeconds <= 0) {
      throw new Error('upstreamTtlSeconds must be a positive finite number');
    }
    this.pruneExpired();
    const ttlSeconds = Math.min(upstreamTtlSeconds, this.maximumTtlSeconds);
    const session: ServerSession = {
      id: randomBytes(32).toString('base64url'),
      accessToken,
      user,
      expiresAt: this.now() + ttlSeconds * 1_000,
    };
    this.sessions.set(session.id, session);
    return session;
  }

  async get(id: string | undefined): Promise<ServerSession | undefined> {
    if (!id) return undefined;
    const session = this.sessions.get(id);
    if (!session) return undefined;
    if (session.expiresAt <= this.now()) {
      this.sessions.delete(id);
      return undefined;
    }
    return session;
  }

  async delete(id: string | undefined): Promise<void> {
    if (id) this.sessions.delete(id);
  }

  get size(): number {
    return this.sessions.size;
  }

  private pruneExpired(): void {
    const now = this.now();
    for (const [id, session] of this.sessions) {
      if (session.expiresAt <= now) this.sessions.delete(id);
    }
  }
}
