import { ActivityIndicator, StyleSheet, View } from 'react-native';
import { NavigationContainer, DefaultTheme, DarkTheme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { AuthEntryScreen } from '../screens/AuthEntryScreen';
import { LoginScreen } from '../screens/LoginScreen';
import { RegisterScreen } from '../screens/RegisterScreen';
import { ForgotPasswordScreen } from '../screens/ForgotPasswordScreen';
import { VerifyPhoneScreen } from '../screens/VerifyPhoneScreen';
import { AppTabs } from './AppTabs';
import { useAuth } from '../context/AuthContext';
import { useTheme, useThemeSetting } from '../theme';
import { useAuthStackInitialRoute } from './useAuthStackInitialRoute';
import type { AuthStackParamList } from './types';

const AuthStack = createNativeStackNavigator<AuthStackParamList>();
const AppStack = createNativeStackNavigator();

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
  const { bootstrapping, token, phoneVerified } = useAuth();
  const authInitialRoute = useAuthStackInitialRoute(token);
  const c = useTheme();
  const { resolved } = useThemeSetting();

  // Session restore reads SecureStore asynchronously (see AuthContext). Rendering anything
  // route-dependent before it resolves would show Login to an already-signed-in user for a frame.
  if (bootstrapping) {
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
    <NavigationContainer theme={navTheme}>
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
