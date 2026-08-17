import { describe, it, expect, vi, afterEach } from 'vitest';
import { isGoogleLoginConfigured } from './googleIdentity';

afterEach(() => {
  vi.unstubAllEnvs();
  document.head.querySelectorAll('script').forEach((s) => s.remove());
  delete (window as any).google;
});

describe('isGoogleLoginConfigured', () => {
  it('is false when no client id is set', () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', '');
    expect(isGoogleLoginConfigured()).toBe(false);
  });

  it('is true once a client id is set', () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    expect(isGoogleLoginConfigured()).toBe(true);
  });
});

describe('loadGoogleIdentityServices', () => {
  // The module caches its script-loading promise at module scope on purpose (see
  // googleIdentity.ts's own comment -- it's meant to survive every real page mount, so the script
  // is only ever injected once per page load). That same caching means each test here needs a
  // fresh module instance, or one test's cached promise leaks into the next and hides what it's
  // actually testing.
  async function freshLoadGoogleIdentityServices() {
    vi.resetModules();
    const mod = await import('./googleIdentity');
    return mod.loadGoogleIdentityServices;
  }

  it('injects the GIS script into <head> exactly once even across concurrent callers', async () => {
    const loadGoogleIdentityServices = await freshLoadGoogleIdentityServices();
    const promise1 = loadGoogleIdentityServices();
    const promise2 = loadGoogleIdentityServices();

    expect(document.querySelectorAll('script[src="https://accounts.google.com/gsi/client"]')).toHaveLength(1);

    // Simulate the script finishing its load -- jsdom doesn't actually fetch/execute it.
    (window as any).google = { accounts: { id: { initialize: vi.fn(), renderButton: vi.fn() } } };
    document.querySelector('script[src="https://accounts.google.com/gsi/client"]')!.dispatchEvent(new Event('load'));

    const [accountsId1, accountsId2] = await Promise.all([promise1, promise2]);
    expect(accountsId1).toBe(window.google!.accounts.id);
    // Both callers resolved with the same instance from the one shared load, not two races.
    expect(accountsId2).toBe(accountsId1);
  });

  it('resolves immediately, with no new script tag, once window.google is already present', async () => {
    const loadGoogleIdentityServices = await freshLoadGoogleIdentityServices();
    (window as any).google = { accounts: { id: { initialize: vi.fn(), renderButton: vi.fn() } } };

    const accountsId = await loadGoogleIdentityServices();

    expect(accountsId).toBe(window.google!.accounts.id);
    expect(document.querySelectorAll('script[src="https://accounts.google.com/gsi/client"]')).toHaveLength(0);
  });

  it('rejects rather than hanging forever when the script fails to load', async () => {
    const loadGoogleIdentityServices = await freshLoadGoogleIdentityServices();
    const promise = loadGoogleIdentityServices();
    document.querySelector('script[src="https://accounts.google.com/gsi/client"]')!.dispatchEvent(new Event('error'));

    await expect(promise).rejects.toThrow('Failed to load Google Identity Services.');
  });
});
