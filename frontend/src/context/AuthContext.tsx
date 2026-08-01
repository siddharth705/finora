import { createContext, useContext, useState, type ReactNode } from 'react';
import { authApi } from '../api/endpoints';
import { AUTH_CHANGED_EVENT } from './ThemeContext';

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
  ) => Promise<{ phoneVerified: boolean; devOtp: string | null; maskedPhone: string | null }>;
  setPhoneVerified: (verified: boolean) => void;
  logout: () => void;
}

const AuthContext = createContext<AuthState | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(localStorage.getItem('finora_token'));
  const [email, setEmail] = useState<string | null>(localStorage.getItem('finora_email'));
  const [fullName, setFullName] = useState<string | null>(localStorage.getItem('finora_name'));
  const [phoneVerified, setPhoneVerifiedState] = useState<boolean>(localStorage.getItem('finora_phone_verified') === 'true');

  function persist(data: { token: string; refreshToken: string; email: string; fullName: string; phoneVerified: boolean }) {
    localStorage.setItem('finora_token', data.token);
    localStorage.setItem('finora_refresh_token', data.refreshToken);
    localStorage.setItem('finora_email', data.email);
    localStorage.setItem('finora_name', data.fullName);
    localStorage.setItem('finora_phone_verified', String(data.phoneVerified));
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
  ): Promise<{ phoneVerified: boolean; devOtp: string | null; maskedPhone: string | null }> {
    const res = await authApi.register(regEmail, password, name, phoneNumber);
    persist(res.data);
    // devOtp is only ever non-null when no SMS provider is configured (see AuthDtos.AuthResponse) —
    // the caller uses it to show the just-issued code immediately instead of making the user
    // click "Resend" to see any code at all. maskedPhone lets it also show which number that code
    // went to, without a second round trip to /phone/send-otp just to learn it.
    return { phoneVerified: res.data.phoneVerified, devOtp: res.data.devOtp ?? null, maskedPhone: res.data.maskedPhone ?? null };
  }

  function setPhoneVerified(verified: boolean) {
    localStorage.setItem('finora_phone_verified', String(verified));
    setPhoneVerifiedState(verified);
  }

  function logout() {
    // Best-effort: revoke the refresh token server-side so it can't be used again even if
    // someone captured it. Don't block clearing local state on this succeeding — if the
    // network call fails, the user still expects to be logged out locally.
    const refreshToken = localStorage.getItem('finora_refresh_token');
    if (refreshToken) {
      authApi.logout(refreshToken).catch(() => {});
    }
    localStorage.removeItem('finora_token');
    localStorage.removeItem('finora_refresh_token');
    localStorage.removeItem('finora_email');
    localStorage.removeItem('finora_name');
    localStorage.removeItem('finora_phone_verified');
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
