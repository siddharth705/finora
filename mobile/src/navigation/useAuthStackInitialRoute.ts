import { useState } from 'react';

/**
 * Which screen the Auth stack should start on: AuthEntry (Phase 3B's identifier-first entry) for
 * a session that's never been signed in, or Login directly once a signed-in session existed and
 * was cleared -- a forced sign-out (session expiry) or an explicit logout both mean the user
 * already knows they want to sign back in, so re-running the identify step is friction with no
 * benefit. This preserves AuthContext.clearLocalState's own comment ("clearing the token lands
 * on Login"), which held before AuthEntry existed and would otherwise silently regress to landing
 * on AuthEntry instead, now that it's the stack's first screen.
 *
 * Uses React's "adjust state during rendering" pattern (comparing against a previous-token state
 * value, not an effect) precisely so the switch to 'Login' takes effect on the SAME render where
 * `token` first becomes null again -- an effect-based update would only apply a render later, by
 * which point the Auth stack (only mounted while `token === null`) would already have rendered
 * with the stale 'AuthEntry' value once.
 */
export function useAuthStackInitialRoute(token: string | null): 'AuthEntry' | 'Login' {
  const [prevToken, setPrevToken] = useState(token);
  const [route, setRoute] = useState<'AuthEntry' | 'Login'>('AuthEntry');

  if (token !== prevToken) {
    setPrevToken(token);
    if (token === null && prevToken !== null) {
      setRoute('Login');
    }
  }

  return route;
}
