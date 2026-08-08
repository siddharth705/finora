import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

import { isStaleChunkError, recoverFromStaleChunk, RELOAD_COOLDOWN_MS } from './staleChunk';

const MARKER = 'finora:stale-chunk-reload';

let reload: ReturnType<typeof vi.fn>;

beforeEach(() => {
  window.sessionStorage.clear();
  reload = vi.fn();
  // jsdom's location.reload is not writable, so the whole object is replaced. Only `reload` is
  // exercised here; the rest is carried over so nothing else reading location breaks.
  Object.defineProperty(window, 'location', {
    configurable: true,
    value: { ...window.location, reload },
  });
});

afterEach(() => {
  vi.useRealTimers();
  window.sessionStorage.clear();
});

describe('isStaleChunkError', () => {
  /** The exact message from the production incident: Chrome, after the SPA fallback answered a
   *  missing chunk with index.html. */
  it('recognises the MIME-mismatch spelling seen in production', () => {
    const error = new Error(
      'Failed to load module script: Expected a JavaScript-or-Wasm module script but the server ' +
        'responded with a MIME type of "text/html". Strict MIME type checking is enforced for ' +
        'module scripts per HTML spec.'
    );

    expect(isStaleChunkError(error)).toBe(true);
  });

  it('recognises the dynamic-import spelling seen in production', () => {
    const error = new Error(
      'Failed to fetch dynamically imported module: https://app.example.com/assets/Login-00kC5-u3.js'
    );

    expect(isStaleChunkError(error)).toBe(true);
  });

  /** Each engine words this differently, and a user's browser decides which one we get. */
  it.each([
    ['Firefox', 'error loading dynamically imported module'],
    ['Safari', 'Importing a module script failed.'],
    ['Firefox MIME', "The resource was blocked due to MIME type ('text/html') mismatch — 'text/html' is not a valid JavaScript MIME type"],
    ['Vite CSS preload', 'Unable to preload CSS for /assets/index-DWfgTSz4.css'],
  ])('recognises the %s wording', (_engine, message) => {
    expect(isStaleChunkError(new Error(message))).toBe(true);
  });

  it('is case-insensitive, since engines do not agree on capitalisation', () => {
    expect(isStaleChunkError(new Error('FAILED TO FETCH DYNAMICALLY IMPORTED MODULE'))).toBe(true);
  });

  /** The important negative. A reload is a heavy, disorienting response; applying it to ordinary
   *  application bugs would replace a readable error panel with a page that silently reloads once
   *  and then shows the same failure anyway. */
  it.each([
    ['an ordinary render bug', "Cannot read properties of undefined (reading 'map')"],
    ['a failed API call', 'Request failed with status code 500'],
    ['a thrown validation message', 'Amount must be greater than zero'],
    ['a network error', 'NetworkError when attempting to fetch resource.'],
  ])('does not fire on %s', (_case, message) => {
    expect(isStaleChunkError(new Error(message))).toBe(false);
  });

  it('handles non-Error throws without blowing up', () => {
    expect(isStaleChunkError('Failed to fetch dynamically imported module: /a.js')).toBe(true);
    expect(isStaleChunkError(undefined)).toBe(false);
    expect(isStaleChunkError(null)).toBe(false);
    expect(isStaleChunkError({ nope: true })).toBe(false);
  });
});

describe('recoverFromStaleChunk', () => {
  it('reloads the document on a first failure', () => {
    expect(recoverFromStaleChunk()).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  it('records the attempt so a second one can be refused', () => {
    recoverFromStaleChunk();

    expect(window.sessionStorage.getItem(MARKER)).toMatch(/^\d+$/);
  });

  /**
   * The guard, and the reason this module exists in the shape it does. A second failure inside the
   * cooldown means the FRESH html failed too -- so this is a broken deploy, not a stale tab, and
   * reloading again would trap the user in a cycle they cannot read an error through.
   */
  it('refuses a second reload inside the cooldown', () => {
    expect(recoverFromStaleChunk()).toBe(true);
    reload.mockClear();

    expect(recoverFromStaleChunk()).toBe(false);
    expect(reload).not.toHaveBeenCalled();
  });

  /** Sessions outlive deploys. Someone with the app open across two releases should be recovered
   *  both times, which is why the guard expires rather than latching for the session. */
  it('allows another reload once the cooldown has elapsed', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-08T10:00:00Z'));

    expect(recoverFromStaleChunk()).toBe(true);
    reload.mockClear();

    vi.setSystemTime(new Date('2026-08-08T10:00:00Z').getTime() + RELOAD_COOLDOWN_MS + 1);

    expect(recoverFromStaleChunk()).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  /** A marker left by something else, or corrupted, must not be able to permanently disable
   *  recovery -- an unparseable value means "no usable previous attempt", not "refuse forever". */
  it('treats an unparseable marker as no previous attempt', () => {
    window.sessionStorage.setItem(MARKER, 'not-a-timestamp');

    expect(recoverFromStaleChunk()).toBe(true);
    expect(reload).toHaveBeenCalledTimes(1);
  });

  /**
   * Fails CLOSED when storage throws, which some privacy modes do on access rather than returning
   * null. With no way to record an attempt there is no way to bound a loop, and an error panel the
   * user can read beats a reload cycle they cannot escape.
   */
  it('declines to reload when sessionStorage is unavailable', () => {
    const original = Object.getOwnPropertyDescriptor(window, 'sessionStorage');
    Object.defineProperty(window, 'sessionStorage', {
      configurable: true,
      get() {
        throw new DOMException('The operation is insecure.', 'SecurityError');
      },
    });

    expect(recoverFromStaleChunk()).toBe(false);
    expect(reload).not.toHaveBeenCalled();

    if (original) Object.defineProperty(window, 'sessionStorage', original);
  });
});
