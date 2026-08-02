import { createContext, useContext, useState, type ReactNode } from 'react';
import { authApi } from '../api/endpoints';
import { AUTH_CHANGED_EVENT } from './ThemeContext';
import { safeStorage } from '../lib/safeStorage';

interface AuthState {
  token: string | null;
  email: string | null;
  fullName: string | null;
  phoneVerified: boolean;
  // Accepts either an email address or a registered mobile number -- see Login.tsx.
  login: (identifier: string, password: string) => Promise<boolean>;
  register: (
    email: string,
    password: string,
    fullName: string,
    phoneNumber: string
  ) => Promise<{ phoneVerified: boolean }>;
  setPhoneVerified: (verified: boolean) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(safeStorage.getItem('finora_token'));
  const [email, setEmail] = useState<string | null>(safeStorage.getItem('finora_email'));
  const [fullName, setFullName] = useState<string | null>(safeStorage.getItem('finora_name'));
  const [phoneVerified, setPhoneVerifiedState] = useState<boolean>(safeStorage.getItem('finora_phone_verified') === 'true');

  function persist(data: { token: string; refreshToken: string; email: string; fullName: string; phoneVerified: boolean }) {
    safeStorage.setItem('finora_token', data.token);
    safeStorage.setItem('finora_refresh_token', data.refreshToken);
    safeStorage.setItem('finora_email', data.email);
    safeStorage.setItem('finora_name', data.fullName);
    safeStorage.setItem('finora_phone_verified', String(data.phoneVerified));
    setToken(data.token);
    setEmail(data.email);
    setFullName(data.fullName);
    setPhoneVerifiedState(data.phoneVerified);
    // Lets ThemeProvider (mounted above AuthProvider, so it can't consume this state directly)
    // re-pull the account's saved theme now that a token exists, instead of only ever checking
    // for one once on its own initial mount.
    window.dispatchEvent(new Event(AUTH_CHANGED_EVENT));
  }

  // Returns whether the phone is already verified — callers use this to decide whether to
  // route through the OTP verification screen or straight into the app.
  async function login(identifier: string, password: string): Promise<boolean> {
    const res = await authApi.login(identifier, password);
    // res.data.email is always the account's real email address, regardless of whether the
    // user typed their email or their phone number to log in -- nothing downstream needs to
    // know which identifier was actually used.
    persist(res.data);
    return res.data.phoneVerified;
  }

  async function register(
    regEmail: string,
    password: string,
    name: string,
    phoneNumber: string
  ): Promise<{ phoneVerified: boolean }> {
    const res = await authApi.register(regEmail, password, name, phoneNumber);
    persist(res.data);
    // VerifyPhone.tsx fetches the account's real phone number itself (userApi.get(), now that
    // it's authenticated) rather than being handed it via router state -- one less thing this
    // return value needs to carry.
    return { phoneVerified: res.data.phoneVerified };
  }

  function setPhoneVerified(verified: boolean) {
    safeStorage.setItem('finora_phone_verified', String(verified));
    setPhoneVerifiedState(verified);
  }

  function logout() {
    // Best-effort: revoke the refresh token server-side so it can't be used again even if
    // someone captured it. Don't block clearing local state on this succeeding — if the
    // network call fails, the user still expects to be logged out locally.
    const refreshToken = safeStorage.getItem('finora_refresh_token');
    if (refreshToken) {
      authApi.logout(refreshToken).catch(() => {});
    }
    safeStorage.removeItem('finora_token');
    safeStorage.removeItem('finora_refresh_token');
    safeStorage.removeItem('finora_email');
    safeStorage.removeItem('finora_name');
    safeStorage.removeItem('finora_phone_verified');
    setToken(null);
    setEmail(null);
    setFullName(null);
    setPhoneVerifiedState(false);
  }

  return (
    <AuthContext.Provider value={{ token, email, fullName, phoneVerified, login, register, setPhoneVerified, logout }}>
      {children}
    </AuthContext.Provider>
  );
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used within AuthProvider');
  return ctx;
}
