import { useCallback, useEffect, useRef } from 'react';
import { Linking } from 'react-native';
import type { NavigationContainerRefWithCurrent } from '@react-navigation/native';
import type { AppTabParamList } from './types';

export interface EmailChangeDeepLinkParams {
  sessionId: string;
  token: string;
}

/**
 * Parses "finora://email-change-verify?sessionId=...&token=..." -- the one deep link this app
 * currently handles. Deliberately not URL/URLSearchParams (unverified whether those are globally
 * available in this Hermes runtime without a polyfill this repo doesn't have) -- a plain regex
 * plus manual query-string split needs nothing beyond what's already guaranteed.
 *
 * Returns null for anything this app doesn't own (a different path, a malformed URL, missing
 * params), so callers can hand it any URL the OS delivers without a prior "is this ours?" check.
 */
export function parseEmailChangeDeepLink(url: string): EmailChangeDeepLinkParams | null {
  const match = /^finora:\/\/email-change-verify\?(.+)$/.exec(url);
  if (!match) return null;

  const params: Record<string, string> = {};
  for (const pair of match[1].split('&')) {
    const [key, value] = pair.split('=');
    if (key && value !== undefined) params[decodeURIComponent(key)] = decodeURIComponent(value);
  }
  if (!params.sessionId || !params.token) return null;
  return { sessionId: params.sessionId, token: params.token };
}

/**
 * Self-review finding (Phase 4 mobile, after the fact): RootNavigator mounts one of three
 * entirely different navigator trees depending on auth state (AuthStack, a bare VerifyPhone
 * AppStack, or AppTabs), but React Navigation's own declarative `linking.config` can only resolve
 * a path against whichever tree happens to be mounted when the URL arrives. A signed-out (or
 * phone-unverified) user tapping the emailed "Open in the Finora app" link would have the deep
 * link silently dropped -- no error, sessionId/token just gone, no way back short of reopening the
 * email -- since AppTabs.More.VerifyEmailChange doesn't exist in the currently-mounted tree.
 *
 * This hook replaces that declarative config for this one path with an imperative, auth-state-
 * aware version: it listens for the raw URL independently of whichever tree React Navigation has
 * mounted (Linking.addEventListener/getInitialURL work at the RN-core level, unrelated to
 * NavigationContainer's own linking integration), stashes it if the app isn't `ready` yet, and
 * replays it via `navigationRef` the moment `ready` flips true -- i.e. the moment sign-in and
 * phone verification complete and AppTabs actually mounts. A `ready` app with the link already in
 * hand still navigates immediately, so this fully replaces (not supplements) the declarative
 * config; RootNavigator no longer registers `email-change-verify` there, avoiding two independent
 * mechanisms racing to navigate the same screen for the same event.
 *
 * `pendingRef`, not state: this only ever needs to be read/cleared from inside an event
 * handler/effect, never during render, so a ref avoids an extra render on every incoming URL for
 * no observable benefit.
 */
export function useEmailChangeDeepLink(
  navigationRef: NavigationContainerRefWithCurrent<AppTabParamList>,
  ready: boolean,
) {
  const pendingRef = useRef<EmailChangeDeepLinkParams | null>(null);
  // Mirrors `ready` into a ref rather than reading the prop directly from tryConsume, so
  // tryConsume itself can stay referentially stable (useCallback, empty deps) instead of being
  // redefined -- and its effects re-subscribed -- on every `ready` change.
  const readyRef = useRef(ready);

  const tryConsume = useCallback(() => {
    if (!readyRef.current) return;
    if (!navigationRef.current || !navigationRef.isReady()) return;
    const params = pendingRef.current;
    if (!params) return;
    pendingRef.current = null;
    navigationRef.navigate('More', { screen: 'VerifyEmailChange', params });
  }, [navigationRef]);

  useEffect(() => {
    function handleUrl(url: string) {
      const parsed = parseEmailChangeDeepLink(url);
      if (!parsed) return;
      pendingRef.current = parsed;
      tryConsume();
    }

    void Linking.getInitialURL().then((url) => { if (url) handleUrl(url); });
    const subscription = Linking.addEventListener('url', (event) => handleUrl(event.url));
    return () => subscription.remove();
  }, [tryConsume]);

  useEffect(() => {
    readyRef.current = ready;
    tryConsume();
  }, [ready, tryConsume]);

  // Wired to NavigationContainer's own onReady prop: navigationRef.isReady() can still be false
  // on the very first render where `ready` flips true (AppTabs mounting is not synchronous with
  // this hook's effect), so this is the retry for that specific gap -- not a duplicate mechanism.
  return { onNavigationReady: tryConsume };
}
