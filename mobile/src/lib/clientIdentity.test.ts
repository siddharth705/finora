import { readFileSync } from 'fs';
import { join } from 'path';
import { Platform } from 'react-native';
import { APP_VERSION, clientPlatform } from './clientIdentity';

/**
 * The client-identity contract, and specifically the one way it can rot silently.
 *
 * APP_VERSION is a hand-maintained constant rather than a read of `expo-constants`, which is not a
 * dependency of this app. That trade is only safe if drift is caught mechanically: bump
 * app.config.ts for a release, forget this file, and every support ticket from then on reports a
 * version the app has not been for months -- with nothing failing to say so.
 */
describe('APP_VERSION', () => {
  it('matches the version Expo actually ships', () => {
    // Read as TEXT, not imported. app.config.ts imports @sentry/react-native/expo, which drags in
    // Expo's config plugins and the `xcode` package -- untransformable under jest-expo, so
    // `require`ing the config here fails on an ESM `export` token before it can tell us anything.
    // A literal read has no such dependency, and the assertion below makes a formatting change
    // fail loudly rather than silently stop checking.
    const source = readFileSync(join(__dirname, '..', '..', 'app.config.ts'), 'utf8');
    const declared = source.match(/^\s*version: '([^']+)',/m);

    expect(declared).not.toBeNull();
    expect(APP_VERSION).toBe(declared![1]);
  });

  it('fits the backend column, which discards anything longer', () => {
    // app_version is VARCHAR(32) in V145/V148, and ClientIdentity drops an over-long value rather
    // than truncating it -- so an over-long version here would silently record nothing at all.
    expect(APP_VERSION.length).toBeLessThanOrEqual(32);
    expect(APP_VERSION.trim()).toBe(APP_VERSION);
  });
});

describe('clientPlatform', () => {
  const original = Platform.OS;
  afterEach(() => {
    Object.defineProperty(Platform, 'OS', { value: original, configurable: true });
  });

  const as = (os: string) =>
    Object.defineProperty(Platform, 'OS', { value: os, configurable: true });

  it('reports the two native platforms the backend enum knows', () => {
    as('ios');
    expect(clientPlatform()).toBe('MOBILE_IOS');
    as('android');
    expect(clientPlatform()).toBe('MOBILE_ANDROID');
  });

  /**
   * Expo web, and anything else unexpected, degrades to WEB rather than inventing a platform --
   * matching the backend's own fallback for an absent or unrecognised header. The value groups
   * support requests; guessing would corrupt exactly the counts it exists to produce.
   */
  it('falls back to WEB for anything that is not a known native platform', () => {
    as('web');
    expect(clientPlatform()).toBe('WEB');
    as('windows');
    expect(clientPlatform()).toBe('WEB');
  });
});
