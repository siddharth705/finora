import { describe, it, expect, vi, afterEach } from 'vitest';
import { isAppleLoginConfigured } from './appleIdentity';

afterEach(() => {
  vi.unstubAllEnvs();
  document.head.querySelectorAll('script').forEach((s) => s.remove());
  delete (window as any).AppleID;
});

describe('isAppleLoginConfigured', () => {
  it('is false when no client id is set', () => {
    vi.stubEnv('VITE_APPLE_LOGIN_CLIENT_ID', '');
    vi.stubEnv('VITE_APPLE_LOGIN_REDIRECT_URI', 'https://finora.app/auth');
    expect(isAppleLoginConfigured()).toBe(false);
  });

  it('is false when no redirect URI is set', () => {
    vi.stubEnv('VITE_APPLE_LOGIN_CLIENT_ID', 'com.finora.web');
    vi.stubEnv('VITE_APPLE_LOGIN_REDIRECT_URI', '');
    expect(isAppleLoginConfigured()).toBe(false);
  });

  it('is true once both a client id and redirect URI are set', () => {
    vi.stubEnv('VITE_APPLE_LOGIN_CLIENT_ID', 'com.finora.web');
    vi.stubEnv('VITE_APPLE_LOGIN_REDIRECT_URI', 'https://finora.app/auth');
    expect(isAppleLoginConfigured()).toBe(true);
  });
});

describe('loadAppleIdServices', () => {
  // Same module-scope caching rationale as googleIdentity.test.ts's freshLoadGoogleIdentityServices
  // -- a fresh module instance per test, or one test's cached promise leaks into the next.
  async function freshLoadAppleIdServices() {
    vi.resetModules();
    const mod = await import('./appleIdentity');
    return mod.loadAppleIdServices;
  }

  const SCRIPT_SRC = 'https://appleid.cdn-apple.com/appleauth/static/jsapi/appleid/1/en_US/appleid.auth.js';

  it('injects the Apple ID JS script into <head> exactly once even across concurrent callers', async () => {
    const loadAppleIdServices = await freshLoadAppleIdServices();
    const promise1 = loadAppleIdServices();
    const promise2 = loadAppleIdServices();

    expect(document.querySelectorAll(`script[src="${SCRIPT_SRC}"]`)).toHaveLength(1);

    (window as any).AppleID = { auth: { init: vi.fn(), signIn: vi.fn() } };
    document.querySelector(`script[src="${SCRIPT_SRC}"]`)!.dispatchEvent(new Event('load'));

    const [auth1, auth2] = await Promise.all([promise1, promise2]);
    expect(auth1).toBe(window.AppleID!.auth);
    expect(auth2).toBe(auth1);
  });

  it('resolves immediately, with no new script tag, once window.AppleID is already present', async () => {
    const loadAppleIdServices = await freshLoadAppleIdServices();
    (window as any).AppleID = { auth: { init: vi.fn(), signIn: vi.fn() } };

    const auth = await loadAppleIdServices();

    expect(auth).toBe(window.AppleID!.auth);
    expect(document.querySelectorAll(`script[src="${SCRIPT_SRC}"]`)).toHaveLength(0);
  });

  it('rejects rather than hanging forever when the script fails to load', async () => {
    const loadAppleIdServices = await freshLoadAppleIdServices();
    const promise = loadAppleIdServices();
    document.querySelector(`script[src="${SCRIPT_SRC}"]`)!.dispatchEvent(new Event('error'));

    await expect(promise).rejects.toThrow('Failed to load Sign in with Apple JS.');
  });
});
