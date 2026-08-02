/**
 * localStorage wrapper that never throws. Plain `localStorage.getItem/setItem/removeItem` calls
 * were scattered across AuthContext, api/client.ts, and ChangePasswordModal -- several of them
 * running directly inside a `useState` initializer, i.e. during render, with no error boundary
 * anywhere in the app to catch what happens next. In a browser/profile that blocks storage access
 * outright (Safari's "Block All Cookies," a privacy extension, a storage-partitioned embed),
 * `localStorage.getItem` throws a SecurityError on the very first call, crashing the whole React
 * tree to a blank white screen before the login form can even render. Every call site should go
 * through this instead: a failed read behaves like "nothing stored" (null), a failed write is a
 * silent no-op (the session just won't persist across a reload in that browser -- no worse than
 * today's crash, and the app stays usable for the current tab).
 */
export const safeStorage = {
  getItem(key: string): string | null {
    try {
      return localStorage.getItem(key);
    } catch {
      return null;
    }
  },
  setItem(key: string, value: string): void {
    try {
      localStorage.setItem(key, value);
    } catch {
      // no-op -- see module doc comment
    }
  },
  removeItem(key: string): void {
    try {
      localStorage.removeItem(key);
    } catch {
      // no-op -- see module doc comment
    }
  },
};
