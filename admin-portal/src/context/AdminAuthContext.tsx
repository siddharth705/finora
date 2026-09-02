import { createContext, useContext, useEffect, useState, type ReactNode } from 'react';
import { authApi, meApi } from '../api/endpoints';
import { clearAdminSession, getAdminToken, persistAdminSession, refreshAccessToken } from '../api/client';
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
  // Layout Studio's upload. Separate from PLATFORM_DIAGNOSTICS_VIEW, which reads the reports:
  // running the engine on a document is an action, and V61 made the two separately grantable.
  'ENGINE_ANALYSIS_RUN',
  // The merchant learning queue's operator surface. Its own permission rather than a reuse: V63
  // records why retrying an event is an action, not a diagnostics view.
  'LEARNING_QUEUE_MANAGE',
  // The Merchant Review Center. Separate from MERCHANT_MANAGE (curating one user's merchants while
  // helping them) because this is working a cross-user queue of the engine's own guesses -- V64.
  'MERCHANT_REVIEW',
  // Subscriptions page (D-28 PR4-A) -- gates the page/sidebar entry, same as every other _VIEW
  // permission here. SUBSCRIPTION_MANAGEMENT_MANAGE (the plan-change mutation) is deliberately NOT
  // listed: nothing in the frontend gates on it directly (the page always renders the dropdown;
  // the backend alone rejects an unauthorized change), so it isn't a portal-entry permission.
  'SUBSCRIPTION_MANAGEMENT_VIEW',
  // Referrals page (D-28 PR4-C) -- same reasoning as SUBSCRIPTION_MANAGEMENT_VIEW immediately
  // above: REFERRAL_MANAGEMENT_MANAGE (crediting a reward) is deliberately NOT listed, since the
  // frontend always renders the "Credit Reward" button and only the backend rejects an
  // unauthorized attempt.
  'REFERRAL_MANAGEMENT_VIEW',
  // Insight Explorer, Phase 2's Founder Operations Dashboard (docs/proposals/reconciliation-
  // evolution-roadmap-proposal.md, Part 9). Its own permission rather than a reuse of USER_VIEW or
  // RECONCILIATION_VIEW -- this is the first admin surface exposing a user's actual computed
  // spend/category/merchant amounts (AdminInsightsExplorerController).
  'INSIGHTS_EXPLORER_VIEW',
  // The admin notification dashboard (Task 12, V128). Its own permission rather than a reuse of
  // PLATFORM_DIAGNOSTICS_VIEW -- see AdminNotificationController's own doc comment. Read-only
  // today, but still gates portal entry the same as every other permission in this list: a role
  // holding only NOTIFICATION_MANAGE must still get past this check to reach the one section it
  // can use, same as the RELATIONSHIP_MANAGE bug this file's own doc comment records.
  'NOTIFICATION_MANAGE',
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
  completeMfaChallenge: (challengeToken: string, code: string) => Promise<boolean>;
  completePhoneVerification: () => Promise<void>;
  logout: () => void;
  hasPermission: (permission: string) => boolean;
}

const AdminAuthContext = createContext<AdminAuthState | null>(null);

/** Thrown by login() with a message already safe to show the user directly -- LoginPage doesn't
 *  need to know the difference between "wrong password," "not phone-verified," and "no admin
 *  permissions," it just displays whatever this carries.
 *
 *  `code`/`details` added for Admin MFA UI (SEC-03): AUTH_MFA_REQUIRED (wire code "AUTH_008")
 *  carries a `mfaChallengeToken` in `details` that Login.tsx needs to start the MFA-challenge
 *  step -- a message-only error had nowhere for that to travel. Both optional and unused by every
 *  other failure this constructs (wrong password, not phone-verified, no admin permission), which
 *  keep working exactly as before. */
export class AdminAccessError extends Error {
  code?: string;
  details?: Record<string, unknown>;
  constructor(message: string, code?: string, details?: Record<string, unknown>) {
    super(message);
    this.code = code;
    this.details = details;
  }
}

export function AdminAuthProvider({ children }: { children: ReactNode }) {
  // SEC-01 (docs/quality/bug-reports/2026-08-19-security-review-findings.md). getAdminToken()
  // used to read a synchronously-available localStorage copy -- that copy is gone now (client.ts's
  // in-memory accessToken variable is the only place the token lives), so this always starts null
  // and the mount effect below is what turns "nothing yet" into "logged out" or "logged in" via a
  // silent refresh against the HttpOnly refresh cookie.
  const [token, setToken] = useState<string | null>(null);
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
  // SEC-01: always starts true now -- the mount effect below always attempts a silent refresh
  // first (there is no synchronously-known token to check anymore), and ProtectedRoute waits for
  // this before deciding whether to redirect, so a reload doesn't flash a "not authorized" bounce
  // while that attempt (and, if it succeeds, the /users/me/access call after it) is in flight.
  const [loading, setLoading] = useState<boolean>(true);

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

  // SEC-01 bootstrap. The access token no longer survives a reload on its own (it's in-memory
  // only, see client.ts), but the HttpOnly refresh cookie does -- so a returning, still-logged-in
  // admin is recovered by attempting one silent refresh on mount before any of the existing
  // phoneVerified/loadAccess logic runs. A failure here (no cookie, or an expired/already-consumed
  // one) is the ordinary "not logged in" case for a first visit or a genuinely ended session.
  //
  // Bug fix: calls client.ts's refreshAccessToken() rather than authApi.refresh() directly -- see
  // that function's own comment for why a raw call here is a real bug. React.StrictMode
  // double-invokes this effect on every real mount, and the cleanup below only gates the state
  // updates that follow, not the network request the first invocation already sent -- so a raw
  // authApi.refresh() call here sent two real requests racing to rotate the same refresh token.
  useEffect(() => {
    let cancelled = false;
    void (async () => {
      let refreshedToken: string;
      try {
        const refreshed = await refreshAccessToken();
        if (cancelled) return;
        persistAdminSession(refreshed.token);
        refreshedToken = refreshed.token;
        setToken(refreshedToken);
      } catch {
        if (!cancelled) setLoading(false);
        return; // no valid session to recover
      }

      if (!phoneVerified) {
        // Reloaded mid-verification (e.g. sitting on /verify-phone) -- nothing to fetch yet, and
        // definitely not a reason to clear a token this bootstrap just confirmed is valid.
        // completePhoneVerification() is what eventually calls loadAccess() once verification
        // actually finishes.
        if (!cancelled) setLoading(false);
        return;
      }

      try {
        await loadAccess();
      } catch {
        // Covers both "no admin permission" (AdminAccessError, already cleared above) and a
        // dead/expired token client.ts's own interceptor couldn't refresh -- either way, there's
        // no valid admin session to keep waiting on.
        clearAdminSession();
        if (!cancelled) setToken(null);
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps -- runs exactly once, on mount, by design.
  }, []);

  /** Shared by login() and completeMfaChallenge() below -- once EITHER a password or a completed
   *  MFA challenge has produced a real session, applying it (persist the token, sync local state,
   *  decide phone-verification vs. loadAccess()) is identical regardless of which one it was. */
  async function applySuccessfulAuth(response: {
    token: string; email: string; fullName: string; phoneVerified: boolean;
  }): Promise<boolean> {
    // BH-012: response.refreshToken is deliberately not passed through to persistAdminSession --
    // see that function's own comment in client.ts. The same token already arrived as an
    // HttpOnly cookie the browser attaches itself.
    persistAdminSession(response.token);
    safeStorage.setItem('finora_admin_email', response.email);
    safeStorage.setItem('finora_admin_name', response.fullName);
    setToken(response.token);
    setEmail(response.email);
    setFullName(response.fullName);
    setPhoneVerified(response.phoneVerified);

    // The response already tells us whether verification is needed -- checking it here, rather
    // than always calling loadAccess() and reacting to the 403 it'd get back, is what ADR-0001
    // means by reusing the existing capability: the info was already available, it was just
    // being ignored. When phoneVerified is false, Login.tsx routes to /verify-phone instead of
    // calling loadAccess() (which would only fail); completePhoneVerification() below is what
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

  async function login(identifier: string, password: string): Promise<boolean> {
    let response;
    try {
      response = await authApi.login(identifier, password);
    } catch (err: any) {
      // Never PHONE_VERIFICATION_REQUIRED here -- /auth/login is excluded from
      // PhoneVerificationFilter, and AuthService.login() itself never checks phone status either,
      // so login always succeeds regardless of verification state. See loadAccess() below for
      // where that error can actually occur.
      //
      // AUTH_MFA_REQUIRED (an MFA-enabled admin, password correct) IS expected here -- errorCode
      // and details (carrying mfaChallengeToken) are threaded through so Login.tsx can start the
      // MFA-challenge step instead of just displaying this as a failure.
      throw new AdminAccessError(
        err?.response?.data?.message ?? 'Sign in failed. Check your credentials and try again.',
        err?.response?.data?.errorCode,
        err?.response?.data?.details,
      );
    }
    return applySuccessfulAuth(response);
  }

  /** Finishes a login that came back AUTH_MFA_REQUIRED -- Login.tsx calls this instead of login()
   *  once the admin has entered their authenticator code (or a recovery code). Same
   *  challengeToken can only be consumed once (AdminMfaService.verifyChallenge marks it used on
   *  success), so a wrong code here just throws and the admin retries with the same token. */
  async function completeMfaChallenge(challengeToken: string, code: string): Promise<boolean> {
    let response;
    try {
      response = await authApi.verifyMfa(challengeToken, code);
    } catch (err: any) {
      throw new AdminAccessError(
        err?.response?.data?.message ?? "That code didn't work. Check your authenticator app and try again.");
    }
    return applySuccessfulAuth(response);
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
    // Best-effort: revoke the refresh token server-side so it can't be used again even if
    // someone captured it. Don't block clearing local state on this succeeding -- if the
    // network call fails, the admin still expects to be logged out locally.
    // BH-012: the cookie is what identifies the session to revoke, and the browser attaches it
    // automatically -- there is nothing for this call to carry. Still gated on believing there IS
    // a session, using the access token as that proxy (client.ts's interceptor uses the same
    // one), where this used to gate on holding a readable refresh token. Logging out when nobody
    // is logged in should stay a local no-op rather than a pointless request. Mirrors
    // frontend/src/context/AuthContext.tsx's logout() exactly.
    if (getAdminToken()) {
      authApi.logout().catch(() => {});
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
      value={{ token, email, fullName, phoneVerified, permissions, roles, loading, login, completeMfaChallenge, completePhoneVerification, logout, hasPermission }}
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
