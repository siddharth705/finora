import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AuthProvider, useAuth } from './AuthContext';
import { authApi } from '../api/endpoints';

/**
 * AuthContext itself had no direct test -- every other test in this codebase mocks useAuth()
 * wholesale (see ProtectedRoute.test.tsx's own comment on why that's the right call for a
 * component that only *consumes* auth state). That leaves the actual session logic in this file
 * -- what login()/register()/logout() persist to storage, and that logout() never lets a failed
 * network call block clearing the local session -- with no coverage of its own. This file closes
 * that gap by rendering the real AuthProvider against a mocked authApi.
 */

vi.mock('../api/endpoints', () => ({
  authApi: { login: vi.fn(), register: vi.fn(), logout: vi.fn() },
}));

const AUTH_RESPONSE = {
  token: 'access-token-1',
  refreshToken: 'refresh-token-1',
  email: 'jane@example.com',
  fullName: 'Jane Doe',
  phoneVerified: true,
  maskedPhone: null,
};

/** Exercises the real hook through a small harness, same pattern other hook-focused tests in
 *  this codebase use when there's no dedicated page already wired up to the flow being tested. */
function Harness() {
  const { token, email, fullName, phoneVerified, login, register, logout } = useAuth();
  return (
    <div>
      <p data-testid="token">{token ?? 'none'}</p>
      <p data-testid="email">{email ?? 'none'}</p>
      <p data-testid="fullName">{fullName ?? 'none'}</p>
      <p data-testid="phoneVerified">{String(phoneVerified)}</p>
      <button onClick={() => void login('jane@example.com', 'password123')}>Log in</button>
      {/* Phone number matches the synthetic placeholder already used by
          VerifyPhone.test.tsx/ChangePasswordModal.test.tsx elsewhere in this codebase. */}
      <button onClick={() => void register('jane@example.com', 'password123', 'Jane Doe', '+919876543705' /* synthetic-ok */)}>
        Register
      </button>
      <button onClick={logout}>Log out</button>
    </div>
  );
}

function renderHarness() {
  return render(
    <AuthProvider>
      <Harness />
    </AuthProvider>
  );
}

describe('AuthContext', () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(authApi.login).mockReset();
    vi.mocked(authApi.register).mockReset();
    vi.mocked(authApi.logout).mockReset();
  });

  it('starts with no session when storage is empty', () => {
    renderHarness();
    expect(screen.getByTestId('token')).toHaveTextContent('none');
    // phoneVerified defaults to TRUE when nothing is stored, matching AdminAuthContext. A missing
    // key means "we haven't heard from the backend", not "not verified" -- and ProtectedRoute
    // redirects to /verify-phone on this flag alone, before any round-trip, so treating absence
    // as false traps an already-verified user on that screen with no client-side way out.
    // Reachable without an upgrade: safeStorage no-ops silently on write failure, and persist()
    // writes five keys in sequence, so a quota failure partway leaves finora_token stored and
    // finora_phone_verified absent. PhoneVerificationFilter remains the real gate either way.
    expect(screen.getByTestId('phoneVerified')).toHaveTextContent('true');
  });

  it('treats an explicitly stored false as unverified', () => {
    localStorage.setItem('finora_phone_verified', 'false');
    renderHarness();
    expect(screen.getByTestId('phoneVerified')).toHaveTextContent('false');
  });

  it('login() persists the full session to storage and updates context state', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockResolvedValue({ data: AUTH_RESPONSE } as any);
    renderHarness();

    await user.click(screen.getByRole('button', { name: 'Log in' }));

    await waitFor(() => expect(screen.getByTestId('token')).toHaveTextContent('access-token-1'));
    expect(screen.getByTestId('email')).toHaveTextContent('jane@example.com');
    expect(screen.getByTestId('fullName')).toHaveTextContent('Jane Doe');
    expect(screen.getByTestId('phoneVerified')).toHaveTextContent('true');

    // The actual persistence contract other code depends on (api/client.ts's request interceptor
    // reads finora_token directly; clearSessionAndRedirect() clears exactly these same keys).
    expect(localStorage.getItem('finora_token')).toBe('access-token-1');
    expect(localStorage.getItem('finora_email')).toBe('jane@example.com');
    expect(localStorage.getItem('finora_name')).toBe('Jane Doe');
    expect(localStorage.getItem('finora_phone_verified')).toBe('true');

    // BH-012. The refresh token is the durable credential -- good for up to the absolute session
    // cap, where the access token above is good for fifteen minutes. It arrives as an HttpOnly
    // cookie precisely so script cannot read it, and this used to write a second copy right here
    // where any XSS could, making the cookie decorative. Asserting its ABSENCE is the only form
    // of this test that fails if someone reinstates the convenience.
    expect(localStorage.getItem('finora_refresh_token')).toBeNull();
    // Not just that one key: the value must not have been persisted under ANY name, or the next
    // person to "helpfully" stash it somewhere else reopens the hole with this test still green.
    expect(Object.values(localStorage)).not.toContain('refresh-token-1');
  });

  it('register() persists the session the same way login() does', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.register).mockResolvedValue({ data: { ...AUTH_RESPONSE, phoneVerified: false } } as any);
    renderHarness();

    await user.click(screen.getByRole('button', { name: 'Register' }));

    await waitFor(() => expect(screen.getByTestId('token')).toHaveTextContent('access-token-1'));
    expect(screen.getByTestId('phoneVerified')).toHaveTextContent('false');
    expect(localStorage.getItem('finora_phone_verified')).toBe('false');
  });

  it('logout() clears the local session even when the best-effort server revoke call fails', async () => {
    const user = userEvent.setup();
    vi.mocked(authApi.login).mockResolvedValue({ data: AUTH_RESPONSE } as any);
    vi.mocked(authApi.logout).mockRejectedValue(new Error('network down'));
    renderHarness();

    await user.click(screen.getByRole('button', { name: 'Log in' }));
    await waitFor(() => expect(screen.getByTestId('token')).toHaveTextContent('access-token-1'));

    await user.click(screen.getByRole('button', { name: 'Log out' }));

    // logout() must never let a rejected authApi.logout() promise stop local state/storage from
    // clearing -- the whole point of it being "best-effort" (see AuthContext.tsx's own comment).
    await waitFor(() => expect(screen.getByTestId('token')).toHaveTextContent('none'));
    expect(screen.getByTestId('phoneVerified')).toHaveTextContent('false');
    expect(localStorage.getItem('finora_token')).toBeNull();
    expect(localStorage.getItem('finora_email')).toBeNull();
    expect(localStorage.getItem('finora_name')).toBeNull();
    expect(localStorage.getItem('finora_phone_verified')).toBeNull();
    // BH-012: no argument. The session to revoke is identified by the HttpOnly cookie the browser
    // attaches automatically, not by a token this app is able to read.
    expect(authApi.logout).toHaveBeenCalledWith();
  });

  it('logout() is a safe no-op call when there is no session to revoke', async () => {
    const user = userEvent.setup();
    renderHarness();

    await user.click(screen.getByRole('button', { name: 'Log out' }));

    expect(authApi.logout).not.toHaveBeenCalled();
    expect(screen.getByTestId('token')).toHaveTextContent('none');
  });
});
