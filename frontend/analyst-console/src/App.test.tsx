import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import App from './App';

const CSRF = 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMN_12';

function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json' },
  });
}

function anonymousResponse(): Response {
  return jsonResponse({ error: { code: 'UNAUTHENTICATED', message: 'Sign in to continue.' } }, 401);
}

function authenticatedResponse(expiresAt = '2026-08-15T12:00:00.000Z'): Response {
  return jsonResponse({
    state: 'authenticated',
    user: { username: 'analyst', role: 'ANALYST' },
    expiresAt,
  });
}

beforeEach(() => {
  localStorage.clear();
  document.cookie = `argus_csrf=${CSRF}; path=/`;
});

afterEach(() => {
  vi.unstubAllGlobals();
  document.cookie = 'argus_csrf=; Max-Age=0; path=/';
});

describe('protected analyst console', () => {
  it('keeps the investigation page hidden until the session check resolves', async () => {
    let resolveSession!: (response: Response) => void;
    const pending = new Promise<Response>((resolve) => { resolveSession = resolve; });
    vi.stubGlobal('fetch', vi.fn(() => pending));

    render(<App />);
    expect(screen.getByText(/checking your secure session/i)).toBeInTheDocument();
    expect(screen.queryByText(/run an investigation/i)).not.toBeInTheDocument();

    resolveSession(anonymousResponse());
    expect(await screen.findByRole('heading', { name: /sign in to argus/i })).toBeInTheDocument();
  });

  it('logs in, renders the protected page, and logs out without exposing a token', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(anonymousResponse())
      .mockResolvedValueOnce(authenticatedResponse())
      .mockResolvedValueOnce(new Response(null, { status: 204 }));
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();

    render(<App />);
    await user.type(await screen.findByLabelText(/username/i), 'analyst');
    await user.type(screen.getByLabelText(/password/i), 'analyst12345');
    await user.click(screen.getByRole('button', { name: /^sign in$/i }));

    expect(await screen.findByText(/run an investigation/i)).toBeInTheDocument();
    expect(screen.getByText('analyst')).toBeInTheDocument();
    expect(localStorage.getItem('token')).toBeNull();

    const loginCall = fetchMock.mock.calls[1];
    expect(loginCall?.[0]).toBe('/bff/auth/login');
    const loginHeaders = new Headers(loginCall?.[1]?.headers);
    expect(loginHeaders.get('authorization')).toBeNull();
    expect(loginHeaders.get('x-csrf-token')).toBe(CSRF);

    await user.click(screen.getByRole('button', { name: /sign out/i }));
    expect(await screen.findByRole('heading', { name: /sign in to argus/i })).toBeInTheDocument();
    expect(screen.queryByText(/run an investigation/i)).not.toBeInTheDocument();
  });

  it('shows a normalized login failure and leaves the protected page guarded', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(anonymousResponse())
      .mockResolvedValueOnce(
        jsonResponse({ error: { code: 'INVALID_CREDENTIALS', message: 'Invalid username or password' } }, 401),
      );
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();

    render(<App />);
    await user.type(await screen.findByLabelText(/username/i), 'analyst');
    await user.type(screen.getByLabelText(/password/i), 'wrong-password');
    await user.click(screen.getByRole('button', { name: /^sign in$/i }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password');
    expect(screen.queryByText(/run an investigation/i)).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText(/username/i)).toHaveValue('analyst'));
  });

  it('requires and verifies MFA without exposing the backend challenge', async () => {
    const fetchMock = vi
      .fn<typeof fetch>()
      .mockResolvedValueOnce(anonymousResponse())
      .mockResolvedValueOnce(jsonResponse({
        state: 'mfa_required',
        username: 'analyst',
        methods: ['TOTP'],
        expiresAt: new Date(Date.now() + 300_000).toISOString(),
      }))
      .mockResolvedValueOnce(authenticatedResponse());
    vi.stubGlobal('fetch', fetchMock);
    const user = userEvent.setup();

    render(<App />);
    await user.type(await screen.findByLabelText(/username/i), 'analyst');
    await user.type(screen.getByLabelText(/password/i), 'analyst12345');
    await user.click(screen.getByRole('button', { name: /^sign in$/i }));

    expect(await screen.findByRole('heading', { name: /verify your identity/i })).toBeInTheDocument();
    expect(screen.queryByText(/run an investigation/i)).not.toBeInTheDocument();
    await user.type(screen.getByLabelText(/6-digit authenticator code/i), '123456');
    await user.click(screen.getByRole('button', { name: /^verify$/i }));

    expect(await screen.findByText(/run an investigation/i)).toBeInTheDocument();
    const verifyCall = fetchMock.mock.calls[2];
    expect(verifyCall?.[0]).toBe('/bff/auth/mfa/verify');
    expect(String(verifyCall?.[1]?.body)).toBe(JSON.stringify({ method: 'TOTP', code: '123456' }));
    expect(String(verifyCall?.[1]?.body)).not.toContain('challengeToken');
  });

  it('unmounts protected data at the server-declared session deadline', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn<typeof fetch>().mockResolvedValueOnce(
        authenticatedResponse(new Date(Date.now() + 800).toISOString()),
      ),
    );

    render(<App />);
    expect(await screen.findByText(/run an investigation/i)).toBeInTheDocument();
    expect(await screen.findByRole('heading', { name: /sign in to argus/i }, { timeout: 2_000 })).toBeInTheDocument();
    expect(screen.queryByText(/run an investigation/i)).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toHaveTextContent(/session expired/i);
  });
});
