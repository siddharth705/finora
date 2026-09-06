import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { NavigationContainer, DefaultTheme, DarkTheme, useNavigationContainerRef } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { AuthEntryScreen } from '../screens/AuthEntryScreen';
import { LoginScreen } from '../screens/LoginScreen';
import { RegisterScreen } from '../screens/RegisterScreen';
import { ForgotPasswordScreen } from '../screens/ForgotPasswordScreen';
import { VerifyPhoneScreen } from '../screens/VerifyPhoneScreen';
import { AppTabs } from './AppTabs';
import { OnboardingNavigator } from '../onboarding/OnboardingNavigator';
import { useAuth } from '../context/AuthContext';
import { useTheme, useThemeSetting } from '../theme';
import { useAuthStackInitialRoute } from './useAuthStackInitialRoute';
import { useEmailChangeDeepLink } from './useEmailChangeDeepLink';
import { useNavigationStatePersistence } from './useNavigationStatePersistence';
import type { AppTabParamList, AuthStackParamList } from './types';

const AuthStack = createNativeStackNavigator<AuthStackParamList>();
const AppStack = createNativeStackNavigator();

/**
 * Phase 4's first deep-link consumer: EmailChangeService emails a confirmation link to the new
 * address, and tapping it needs to land on VerifyEmailChangeScreen with sessionId/token intact.
 *
 * Custom scheme only ("finora://email-change-verify?..."), not a true universal/app link
 * ("https://app.fynora.net/email-change-verify?..." routed to the app instead of a browser)
 * -- that needs iOS Associated Domains + a hosted apple-app-site-association file, and Android App
 * Links + a hosted assetlinks.json signed with the release keystore's fingerprint, none of which
 * this repo currently has (and neither is something a code change alone can stand up or verify --
 * it needs real Apple Developer / Play Console access this environment doesn't have). The web
 * confirmation page (VerifyEmailChange.tsx) still emails the same https:// link it always did, so
 * it keeps working from any device or email client exactly as before; it separately offers an
 * "Open in the Finora app" link using this same custom scheme for anyone reading that email on
 * their phone. Revisit true universal links once the native hosting/signing pieces exist.
 *
 * Actual routing for this one path is imperative (useEmailChangeDeepLink below), not React
 * Navigation's own declarative `linking.config` -- see that hook's own doc comment for why: this
 * screen only exists inside the AppTabs tree, but the link can arrive while any of RootNavigator's
 * three mutually-exclusive trees is mounted, including while signed out.
 */
const linkingPrefixes = ['finora://'];

/**
 * The mobile counterpart of the web app's ProtectedRoute, expressed the way React Navigation
 * intends: which stack exists at all is derived from auth state, rather than every screen
 * checking a guard and redirecting. A signed-out user has no route to the app stack to navigate
 * to -- it isn't mounted -- so there's no "flash of authenticated UI" to defend against, and no
 * imperative navigate() call after login/register/verify. Flipping state is the navigation.
 *
 * As on the web, this is UX only. The backend is the real enforcement: PhoneVerificationFilter
 * rejects an unverified account's requests with 403 PHONE_VERIFICATION_REQUIRED regardless of
 * what the client renders.
 */
export function RootNavigator() {
  const { bootstrapping, token, phoneVerified, onboardingCompleted } = useAuth();
  const authInitialRoute = useAuthStackInitialRoute(token);
  const c = useTheme();
  const { resolved } = useThemeSetting();
  const navigationRef = useNavigationContainerRef<AppTabParamList>();
  // AppTabs is actually the mounted tree -- token alone isn't enough, since a
  // signed-in-but-unverified account gets the single-screen VerifyPhone AppStack instead, which
  // has no route to More.VerifyEmailChange either, and a verified-but-not-yet-onboarded account
  // gets OnboardingNavigator instead (see the render logic below). Shared below by the deep-link
  // hook (its own "ready" gate) and the nav-state-persistence hook (its own "which tree does this
  // state belong to" gate) -- both need exactly this condition, not a slightly different one.
  const isAppTabsActive = token !== null && phoneVerified && onboardingCompleted;
  const { onNavigationReady } = useEmailChangeDeepLink(navigationRef, isAppTabsActive, token !== null);
  const navPersistence = useNavigationStatePersistence(bootstrapping, isAppTabsActive);

  // Session restore reads SecureStore asynchronously (see AuthContext). Rendering anything
  // route-dependent before it resolves would show Login to an already-signed-in user for a frame.
  // Also waits on navPersistence: reading its one AsyncStorage key is comparably fast, and folding
  // it into the same spinner avoids a second, separate loading flash right after this one clears.
  if (bootstrapping || !navPersistence.isReady) {
    return (
      <View style={[styles.splash, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  // Stock DefaultTheme/DarkTheme's own `colors.primary` is React Navigation's iOS-blue default,
  // independent of (and previously left to clash with) the app's own palette -- spread the stock
  // theme (it already carries the `fonts` object v7 requires) and override just the colors that
  // matter, so anything React Navigation draws unprompted (native header default, back-gesture
  // tint) matches the rest of the app instead of standing out as a different product. Based on
  // `resolved` from useThemeSetting() rather than the raw OS scheme, so this can't disagree with
  // the theme every other screen is already painted in when the user has picked a manual
  // light/dark override that differs from the system setting.
  const base = resolved === 'dark' ? DarkTheme : DefaultTheme;
  const navTheme = {
    ...base,
    dark: resolved === 'dark',
    colors: {
      ...base.colors,
      primary: c.primary,
      background: c.bg,
      card: c.card,
      text: c.ink,
      border: c.border,
    },
  };

  return (
    <NavigationContainer
      ref={navigationRef}
      theme={navTheme}
      linking={{ prefixes: linkingPrefixes }}
      onReady={onNavigationReady}
      // Both undefined whenever isAppTabsActive is false: navPersistence never populates
      // initialState outside that condition (see the hook's own doc comment), and onStateChange
      // itself no-ops via the same activeRef check. Passing them unconditionally rather than only
      // inside the AppTabs branch below because NavigationContainer is the one component instance
      // wrapping all three conditionally-rendered trees -- it can't take different props per
      // child.
      initialState={navPersistence.initialState}
      onStateChange={navPersistence.onStateChange}
    >
      {token === null ? (
        // initialRouteName -- not just AuthEntry listed first -- because which screen this stack
        // should open on differs by how it got here: a cold, never-signed-in launch starts on
        // AuthEntry (Phase 3B fronts Login/Register the same way web's /auth does, without
        // removing direct access to either); a sign-out from a previously-authenticated session
        // starts on Login directly, per useAuthStackInitialRoute's own doc comment.
        <AuthStack.Navigator screenOptions={{ headerShown: false }} initialRouteName={authInitialRoute}>
          <AuthStack.Screen name="AuthEntry" component={AuthEntryScreen} />
          <AuthStack.Screen name="Login" component={LoginScreen} />
          <AuthStack.Screen name="Register" component={RegisterScreen} />
          <AuthStack.Screen name="ForgotPassword" component={ForgotPasswordScreen} />
        </AuthStack.Navigator>
      ) : !phoneVerified ? (
        // Single screen by design: an unverified account can't reach any other protected
        // endpoint, so there's nowhere else to go until this completes. VerifyPhoneScreen offers
        // sign-out as the way back.
        <AppStack.Navigator screenOptions={{ headerShown: false }}>
          <AppStack.Screen name="VerifyPhone" component={VerifyPhoneScreen} />
        </AppStack.Navigator>
      ) : !onboardingCompleted ? (
        <OnboardingNavigator />
      ) : (
        <AppTabs />
      )}
    </NavigationContainer>
  );
}

const styles = StyleSheet.create({
  splash: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
});
