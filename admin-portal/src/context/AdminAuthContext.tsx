import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { authApi, meApi } from '../api/endpoints';
import { clearAdminSession, getAdminToken, persistAdminSession } from '../api/client';
import { safeStorage } from '../lib/safeStorage';

// Any one of these being present is enough to open the admin shell -- deliberately not "must be
// ADMIN or SUPER_ADMIN," since the backend gates every individual admin endpoint on a specific
// permission (V16__rbac_roles_permissions.sql / V24__admin_platform_stats_permission.sql), not a
// role name. A user with just AUDIT_VIEW (say, a narrowly-scoped support role someone creates
// later via the Roles page) should be able to sign into this portal and see exactly the sections
// their permissions unlock -- see Sidebar.tsx, which hides nav items the caller can't reach
// rather than assuming "logged into the admin app" implies "can see everything in it."
// Kept in sync with every admin-only permission seeded across V16/V24/V25/V26/V47's migrations --
// this list exists purely to gate portal entry, not to control what's visible inside it (that's
// Sidebar.tsx + RequirePermission, per-permission). A narrowly-scoped role that only holds
// BANK_MANAGE or RULE_MANAGE (say) still needs to get past this check to reach the one section it
// can actually use.
//
// Bug fix: RELATIONSHIP_MANAGE (V47__relationship_manage_permission.sql, gates
// AdminUserRelationshipController and UserDetail.tsx's RelationshipsSection) was never added here
// when it was introduced, despite this list's own doc comment claiming it tracks exactly this --
// a role holding RELATIONSHIP_MANAGE and nothing else in this list would be rejected at login with
// "This account doesn't have any admin permissions," unable to reach the one section it can use.
// Exported so AdminAuthContext.permissionCoverage.test.ts can scan every `hasPermission('X')` /
// `permission="X"` site in pages/components and assert this list never drifts out of sync again.
export const ADMIN_PORTAL_PERMISSIONS = [
  'AUDIT_VIEW', 'USER_VIEW', 'USER_CREATE', 'USER_UPDATE', 'USER_DELETE',
  'ACCOUNT_CREATE', 'ACCOUNT_UPDATE', 'ACCOUNT_DELETE', 'TRANSACTION_DELETE',
  'ROLE_MANAGE', 'PERMISSION_MANAGE', 'SYSTEM_SETTINGS', 'PLATFORM_STATS_VIEW',
  'BANK_MANAGE', 'RULE_MANAGE', 'MERCHANT_MANAGE', 'RECONCILIATION_VIEW', 'PLATFORM_ANALYTICS_VIEW',
  'PLATFORM_DIAGNOSTICS_VIEW', 'RELATIONSHIP_MANAGE',
];

export interface AdminAuthState {
  token: string | null;
  email: string | null;
  fullName: string | null;
  phoneVerified: boolean;
  permissions: string[];
  roles: string[];
  loading: boolean;
  // Resolves to whether the phone is already verified -- Login.tsx uses this to decide whether
  // to route to /verify-phone or straight into the dashboard, same pattern as frontend/'s
  // AuthContext.login().
  login: (identifier: string, password: string) => Promise<boolean>;
  completePhoneVerification: () => Promise<void>;
  logout: () => void;
  hasPermission: (permission: string) => boolean;
}

const AdminAuthContext = createContext<AdminAuthState | null>(null);

/** Thrown by login() with a message already safe to show the user directly -- LoginPage doesn't
 *  need to know the difference between "wrong password," "not phone-verified," and "no admin
 *  permissions," it just displays whatever this carries. */
class AdminAccessError extends Error {}

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(getAdminToken());
  const [email, setEmail] = useState<string | null>(safeStorage.getItem('finora_admin_email'));
  const [fullName, setFullName] = useState<string | null>(safeStorage.getItem('finora_admin_name'));
  // Mirrors frontend/'s AuthContext phoneVerified tracking exactly -- see ADR-0001. Defaults to
  // true when there's no stored value yet (e.g. a token from before this field existed) so an
  // already-working session isn't suddenly redirected to /verify-phone by this change; a real
  // false value is only ever set by login()/loadAccess() below once we've actually heard
  // otherwise from the backend.
  const [phoneVerified, setPhoneVerifiedState] = useState<boolean>(
    safeStorage.getItem('finora_admin_phone_verified') !== 'false'
  );
  const [permissions, setPermissions] = useState<string[]>([]);
  const [roles, setRoles] = useState<string[]>([]);
  // Starts true whenever a token already exists (page reload) -- ProtectedRoute waits for this
  // before deciding whether to redirect, so a reload doesn't flash a "not authorized" bounce
  // while the /users/me/access call is still in flight.
  const [loading, setLoading] = useState<boolean>(!!getAdminToken());

  function setPhoneVerified(verified: boolean) {
    safeStorage.setItem('finora_admin_phone_verified', String(verified));
    setPhoneVerifiedState(verified);
  }

  async function loadAccess() {
    let access;
    try {
      access = await meApi.access();
    } catch (err: any) {
      if (err?.response?.data?.errorCode === 'PHONE_VERIFICATION_REQUIRED') {
        // NOT a session failure -- the token is still perfectly valid, the account just hasn't
        // completed the one remaining step. Clearing the session here (as this used to) meant
        // there was no way to ever reach a fix: by the time anything could redirect to
        // /verify-phone, the token needed to call phone/send-otp and phone/verify-otp was
        // already gone. See ADR-0001 -- this reuses the exact same verification flow the user
        // app (frontend/) already has, rather than the admin portal inventing its own.
        setPhoneVerified(false);
        throw new AdminAccessError('Your phone number still needs to be verified.');
      }
      // Every other failure here IS a real session problem (dead/expired token, network error,
      // etc.) -- clearing is correct in this branch, just not the one above.
      clearAdminSession();
      setToken(null);
      throw new AdminAccessError('Could not verify your account access. Please try signing in again.');
    }

    const grantsAdminAccess = access.permissions.some((p) => ADMIN_PORTAL_PERMISSIONS.includes(p));
    if (!grantsAdminAccess) {
      clearAdminSession();
      setToken(null);
      throw new AdminAccessError(
        'This account doesn’t have any admin permissions. Ask a Super Admin to grant one (see Roles & Permissions) if you believe this is a mistake.');
    }
    setPermissions(access.permissions);
    setRoles(access.roles);
  }

  useEffect(() => {
    if (!token) {
      setLoading(false);
      return;
    }
    if (!phoneVerified) {
      // Reloaded mid-verification (e.g. sitting on /verify-phone) -- nothing to fetch yet, and
      // definitely not a reason to clear a perfectly valid token. completePhoneVerification()
      // below is what eventually calls loadAccess() once verification actually finishes.
      setLoading(false);
      return;
    }
    loadAccess()
      .catch(() => {
        // Covers both "no admin permission" (AdminAccessError, already cleared above) and a
        // dead/expired token client.ts's own interceptor couldn't refresh -- either way, there's
        // no valid admin session to keep waiting on. (PHONE_VERIFICATION_REQUIRED can't reach
        // here since phoneVerified is checked above, but loadAccess() still guards it directly.)
        clearAdminSession();
        setToken(null);
      })
      .finally(() => setLoading(false));
    // Intentionally only on mount / when a token first appears -- permissions are re-fetched
    // fresh on every login() call already, and reload is the only case that lands here.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  async function login(identifier: string, password: string): Promise<boolean> {
    let response;
    try {
      response = await authApi.login(identifier, password);
    } catch (err: any) {
      // Never PHONE_VERIFICATION_REQUIRED here -- /auth/login is excluded from
      // PhoneVerificationFilter, and AuthService.login() itself never checks phone status either,
      // so login always succeeds regardless of verification state. See loadAccess() below for
      // where that error can actually occur.
      throw new AdminAccessError(err?.response?.data?.message ?? 'Sign in failed. Check your credentials and try again.');
    }

    persistAdminSession(response.token, response.refreshToken);
    safeStorage.setItem('finora_admin_email', response.email);
    safeStorage.setItem('finora_admin_name', response.fullName);
    setToken(response.token);
    setEmail(response.email);
    setFullName(response.fullName);
    setPhoneVerified(response.phoneVerified);

    // The login response already tells us whether verification is needed -- checking it here,
    // rather than always calling loadAccess() and reacting to the 403 it'd get back, is what
    // ADR-0001 means by reusing the existing capability: the info was already available, it was
    // just being ignored. When phoneVerified is false, Login.tsx routes to /verify-phone instead
    // of calling loadAccess() (which would only fail); completePhoneVerification() below is what
    // calls it once verification actually finishes.
    if (!response.phoneVerified) {
      return false;
    }

    try {
      await loadAccess();
    } catch (err) {
      // loadAccess() already cleared the session on a permissions failure -- also undo the
      // local state this function just set, so the app doesn't think it's half logged-in.
      setEmail(null);
      setFullName(null);
      throw err;
    }
    return true;
  }

  /** Called by VerifyPhone.tsx after a successful OTP check -- marks verification complete and
   *  runs the same permissions fetch login() would have run directly, had phoneVerified already
   *  been true. Throws the same AdminAccessError login() would (e.g. no admin permission),
   *  which VerifyPhone.tsx displays the same way Login.tsx does. */
  async function completePhoneVerification() {
    setPhoneVerified(true);
    try {
      await loadAccess();
    } catch (err) {
      setEmail(null);
      setFullName(null);
      throw err;
    }
  }

  function logout() {
    const refreshToken = safeStorage.getItem('finora_admin_refresh_token');
    if (refreshToken) {
      authApi.logout(refreshToken).catch(() => {});
    }
    clearAdminSession();
    safeStorage.removeItem('finora_admin_email');
    safeStorage.removeItem('finora_admin_name');
    safeStorage.removeItem('finora_admin_phone_verified');
    setToken(null);
    setEmail(null);
    setFullName(null);
    setPhoneVerifiedState(true);
    setPermissions([]);
    setRoles([]);
  }

  function hasPermission(permission: string) {
    return permissions.includes(permission);
  }

  return (
    <AdminAuthContext.Provider
      value={{ token, email, fullName, phoneVerified, permissions, roles, loading, login, completePhoneVerification, logout, hasPermission }}
    >
      {children}
    </AdminAuthContext.Provider>
  );
}

export function useAdminAuth() {
  const ctx = useContext(AdminAuthContext);
  if (!ctx) throw new Error('useAdminAuth must be used within AdminAuthProvider');
  return ctx;
}
