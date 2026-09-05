import { AESEncryptionKey, AESKeySize, AESSealedData, aesDecryptAsync, aesEncryptAsync } from 'expo-crypto';
import { safeStorage } from './safeStorage';
import { decodeUtf8, encodeUtf8 } from './utf8';

/**
 * D4 (Track D security cleanup, docs/project-management/plans/mobile-correctness-trust-roadmap.md).
 * The persisted query cache (dashboard/ledger/budgets/reports figures -- see queryClient.ts's own
 * PERSISTED_QUERY_KEY_PREFIXES allowlist) used to sit in AsyncStorage as plain JSON for up to 24h:
 * readable by anything with filesystem access to the app's sandbox (a backup-extraction tool, a
 * rooted/jailbroken device, another process on a compromised one). This encrypts that blob with
 * AES-256-GCM before it ever reaches AsyncStorage.
 *
 * Envelope encryption, not encrypting AsyncStorage wholesale: the AES key itself lives in
 * SecureStore (Keychain/Keystore) via safeStorage -- small, and exactly what SecureStore is for.
 * The JSON blob itself can run to tens of KB, which is exactly wrong for SecureStore's per-item
 * size ceiling and exactly fine for AsyncStorage. Each keeps doing what it's already good at;
 * only the payload AsyncStorage now holds changes, from plaintext to ciphertext it cannot read.
 *
 * The key is generated once and reused, not per write: GCM's security guarantee depends on never
 * reusing a (key, nonce) pair, and aesEncryptAsync already generates a fresh random nonce on every
 * call by default -- regenerating the KEY on every write would be pure churn for no security gain,
 * and would make every previously-written value permanently undecryptable the moment it happened.
 */
const KEY_STORAGE_KEY = 'finora_query_cache_key';

let cachedKey: AESEncryptionKey | null = null;

async function getOrCreateKey(): Promise<AESEncryptionKey> {
  if (cachedKey) return cachedKey;

  const stored = await safeStorage.getItem(KEY_STORAGE_KEY);
  if (stored) {
    try {
      cachedKey = await AESEncryptionKey.import(stored, 'base64');
      return cachedKey;
    } catch {
      // A corrupted or foreign-format stored key -- fall through and mint a fresh one rather than
      // permanently failing every persistence read/write from here on. Every existing ciphertext
      // under the old key becomes undecryptable, which decryptFromStorage below already treats as
      // "nothing usable was cached" -- the same safe degrade a missing/expired cache already gets,
      // not a new failure mode.
    }
  }

  const key = await AESEncryptionKey.generate(AESKeySize.AES256);
  cachedKey = key;
  await safeStorage.setItem(KEY_STORAGE_KEY, await key.encoded('base64'));
  return key;
}

/**
 * Never throws -- a failed encrypt means the write silently doesn't happen, the same fail-safe
 * posture safeStorage.setItem already has for its own writes, not a crash on a background
 * persistence tick nobody is watching. Returns null on failure so the caller can skip the
 * AsyncStorage write entirely rather than persist a placeholder.
 */
export async function encryptForStorage(plaintext: string): Promise<string | null> {
  try {
    const key = await getOrCreateKey();
    const sealed = await aesEncryptAsync(encodeUtf8(plaintext), key);
    return await sealed.combined('base64');
  } catch {
    return null;
  }
}

/**
 * Never throws -- a failed decrypt (corrupted data, a key that no longer matches, a plaintext
 * value written before this encryption existed) is treated as "nothing usable was cached," which
 * persistQueryClientRestore already handles as an ordinary cold start with no persisted data.
 */
export async function decryptFromStorage(ciphertext: string): Promise<string | null> {
  try {
    const key = await getOrCreateKey();
    const sealed = AESSealedData.fromCombined(ciphertext);
    const bytes = await aesDecryptAsync(sealed, key, { output: 'bytes' });
    return decodeUtf8(bytes.buffer);
  } catch {
    return null;
  }
}
