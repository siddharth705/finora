import * as SecureStore from 'expo-secure-store';

/**
 * SecureStore-backed equivalent of the web app's safeStorage (frontend/src/lib/safeStorage.ts):
 * same never-throws contract (a failed read behaves like "nothing stored", a failed write is a
 * silent no-op), same three-method shape -- but async, not sync. localStorage is synchronous;
 * SecureStore's stable, always-available API is Promise-based (getItemAsync/setItemAsync/
 * deleteItemAsync), so every call site in the mobile app awaits this instead of reading directly.
 * This also means secrets are encrypted at rest (iOS Keychain / Android Keystore) rather than
 * sitting in plaintext localStorage, a strict upgrade for a financial app.
 */
export const safeStorage = {
  async getItem(key: string): Promise<string | null> {
    try {
      return await SecureStore.getItemAsync(key);
    } catch {
      return null;
    }
  },
  async setItem(key: string, value: string): Promise<void> {
    try {
      await SecureStore.setItemAsync(key, value);
    } catch {
      // no-op -- see module doc comment
    }
  },
  async removeItem(key: string): Promise<void> {
    try {
      await SecureStore.deleteItemAsync(key);
    } catch {
      // no-op -- see module doc comment
    }
  },
};
