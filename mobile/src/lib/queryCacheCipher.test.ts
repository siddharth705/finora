/**
 * D4 (Track D security cleanup). expo-crypto's own bundled jest mock (expo-crypto/mocks) is an
 * auto-generated no-op stub -- EncryptionKey has no generate/import, encryptAsync/decryptAsync
 * resolve to undefined -- so it can't stand in for real AES here. This is a small, deterministic,
 * REVERSIBLE fake instead: base64 in place of real ciphertext, tagged with which key "encrypted"
 * it so a decrypt under a different key can be made to fail on purpose (the "wrong/rotated key"
 * test below). It exists to verify this module's own control flow -- generates a key once, reuses
 * it, stores it, degrades gracefully on garbage -- not to exercise real cryptography, which is
 * expo-crypto's own test suite's job.
 *
 * Every test re-fetches both 'expo-secure-store' (via require(), which respects jest's manual-mock
 * substitution the way a dynamic import() of a package unexpectedly did not under this project's
 * --experimental-vm-modules setup) and './queryCacheCipher' (via dynamic import()) AFTER a
 * jest.resetModules(), rather than a static top-level import of either: resetModules() clears the
 * require cache, so a static import captured once at file load would keep pointing at a stale
 * expo-secure-store mock instance (its own fresh, empty Map) the moment any test resets modules.
 */
let mockNextGeneratedKeyId = 0;
const mockGenerate = jest.fn();
const mockImportKey = jest.fn();
const mockAesEncryptAsync = jest.fn();
const mockAesDecryptAsync = jest.fn();

// Classes have to live INSIDE the factory -- jest.mock() factories are hoisted above the rest of
// this file and can only close over `mock`-prefixed bindings (the jest.fn()s above), never an
// out-of-scope class declaration.
jest.mock('expo-crypto', () => {
  class FakeKey {
    id: string;
    constructor(id: string) {
      this.id = id;
    }
    async encoded(): Promise<string> {
      return this.id;
    }
  }
  class FakeSealedData {
    payload: string;
    constructor(payload: string) {
      this.payload = payload;
    }
    static fromCombined(combined: string): FakeSealedData {
      return new FakeSealedData(combined);
    }
    async combined(): Promise<string> {
      return this.payload;
    }
  }

  mockGenerate.mockImplementation(async () => new FakeKey(`generated-${++mockNextGeneratedKeyId}`));
  mockImportKey.mockImplementation(async (input: string) => new FakeKey(input));
  mockAesEncryptAsync.mockImplementation(async (bytes: Uint8Array, key: InstanceType<typeof FakeKey>) => {
    const base64 = Buffer.from(bytes).toString('base64');
    return new FakeSealedData(`${key.id}:${base64}`);
  });
  mockAesDecryptAsync.mockImplementation(async (sealed: InstanceType<typeof FakeSealedData>, key: InstanceType<typeof FakeKey>) => {
    const separatorIndex = sealed.payload.indexOf(':');
    const keyId = sealed.payload.slice(0, separatorIndex);
    const base64 = sealed.payload.slice(separatorIndex + 1);
    if (keyId !== key.id) throw new Error('fake AES: key does not match sealed data');
    return new Uint8Array(Buffer.from(base64, 'base64'));
  });

  return {
    AESKeySize: { AES256: 256 },
    AESEncryptionKey: { generate: mockGenerate, import: mockImportKey },
    AESSealedData: FakeSealedData,
    aesEncryptAsync: mockAesEncryptAsync,
    aesDecryptAsync: mockAesDecryptAsync,
  };
});

const KEY_STORAGE_KEY = 'finora_query_cache_key';

beforeEach(() => {
  jest.clearAllMocks();
  mockNextGeneratedKeyId = 0;
  jest.resetModules();
});

describe('encryptForStorage / decryptFromStorage', () => {
  it('round-trips a JSON payload exactly, including unicode', async () => {
    const { encryptForStorage, decryptFromStorage } = await import('./queryCacheCipher');
    const payload = JSON.stringify({ merchant: 'Café Nero 🎉', amount: -450 });

    const ciphertext = await encryptForStorage(payload);
    expect(ciphertext).not.toBeNull();
    expect(ciphertext).not.toContain('Café'); // Not just base64 of the JSON with nothing hidden.

    expect(await decryptFromStorage(ciphertext as string)).toBe(payload);
  });

  it('generates a key only once and reuses it for later encrypt/decrypt calls in the same process', async () => {
    const { encryptForStorage, decryptFromStorage } = await import('./queryCacheCipher');

    const first = await encryptForStorage('{"a":1}');
    const second = await encryptForStorage('{"b":2}');

    expect(mockGenerate).toHaveBeenCalledTimes(1);
    expect(await decryptFromStorage(first as string)).toBe('{"a":1}');
    expect(await decryptFromStorage(second as string)).toBe('{"b":2}');
  });

  it('persists the generated key to SecureStore, not just in-memory', async () => {
    const SecureStore = require('expo-secure-store') as typeof import('expo-secure-store');
    const { encryptForStorage } = await import('./queryCacheCipher');

    await encryptForStorage('{"a":1}');

    expect(await SecureStore.getItemAsync(KEY_STORAGE_KEY)).toBe('generated-1');
  });

  it('imports the key already in SecureStore instead of generating a new one on a fresh process', async () => {
    const SecureStore = require('expo-secure-store') as typeof import('expo-secure-store');
    await SecureStore.setItemAsync(KEY_STORAGE_KEY, 'already-stored-key');
    const { encryptForStorage, decryptFromStorage } = await import('./queryCacheCipher');

    const ciphertext = await encryptForStorage('{"a":1}');

    expect(mockGenerate).not.toHaveBeenCalled();
    expect(mockImportKey).toHaveBeenCalledWith('already-stored-key', 'base64');
    expect(await decryptFromStorage(ciphertext as string)).toBe('{"a":1}');
  });

  // The scenario this whole module exists to degrade safely from: a value encrypted under a key
  // that no longer matches (rotated, or corrupted in storage) must not crash the cache restore --
  // it has to read back as "nothing usable was cached," the same as an expired or missing entry.
  it('returns null, not a thrown error, when decrypting under a different key than it was written with', async () => {
    const { encryptForStorage } = await import('./queryCacheCipher');
    const ciphertext = await encryptForStorage('{"a":1}');

    // Simulate a later process that ends up with a DIFFERENT (e.g. rotated) key already in
    // SecureStore -- getOrCreateKey succeeds (it's valid key material), it just isn't the one
    // this particular ciphertext was sealed under.
    jest.resetModules();
    const SecureStore = require('expo-secure-store') as typeof import('expo-secure-store');
    await SecureStore.setItemAsync(KEY_STORAGE_KEY, 'a-different-stored-key');
    const { decryptFromStorage: decryptWithDifferentKey } = await import('./queryCacheCipher');

    expect(await decryptWithDifferentKey(ciphertext as string)).toBeNull();
  });

  it('returns null rather than throwing when the stored value is not valid ciphertext at all', async () => {
    // The pre-encryption plaintext-JSON case: a real scenario the first read after upgrading to
    // this fix will hit for whatever was already on disk.
    const { decryptFromStorage } = await import('./queryCacheCipher');
    mockAesDecryptAsync.mockRejectedValueOnce(new Error('not valid sealed data'));

    expect(await decryptFromStorage('{"plain":"json","from":"before encryption existed"}')).toBeNull();
  });

  it('falls back to generating a fresh key rather than failing forever when the stored key is corrupted', async () => {
    const SecureStore = require('expo-secure-store') as typeof import('expo-secure-store');
    await SecureStore.setItemAsync(KEY_STORAGE_KEY, 'garbage');
    mockImportKey.mockRejectedValueOnce(new Error('corrupted key material'));
    const { encryptForStorage, decryptFromStorage } = await import('./queryCacheCipher');

    const ciphertext = await encryptForStorage('{"a":1}');

    expect(mockGenerate).toHaveBeenCalledTimes(1);
    expect(await decryptFromStorage(ciphertext as string)).toBe('{"a":1}');
  });

  it('returns null rather than throwing when encryption itself fails', async () => {
    const { encryptForStorage } = await import('./queryCacheCipher');
    mockAesEncryptAsync.mockRejectedValueOnce(new Error('native module unavailable'));

    expect(await encryptForStorage('{"a":1}')).toBeNull();
  });
});
