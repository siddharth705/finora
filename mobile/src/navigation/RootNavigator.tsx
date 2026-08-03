import { ActivityIndicator, StyleSheet, Text, View } from 'react-native';
import { NavigationContainer, DefaultTheme, DarkTheme } from '@react-navigation/native';
import { createNativeStackNavigator } from '@react-navigation/native-stack';
import { useColorScheme } from 'react-native';
import { LoginScreen } from '../screens/LoginScreen';
import { RegisterScreen } from '../screens/RegisterScreen';
import { ForgotPasswordScreen } from '../screens/ForgotPasswordScreen';
import { VerifyPhoneScreen } from '../screens/VerifyPhoneScreen';
import { useAuth } from '../context/AuthContext';
import { useTheme } from '../theme';
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
  const c = useTheme();
  const scheme = useColorScheme();

  // Session restore reads SecureStore asynchronously (see AuthContext). Rendering anything
  // route-dependent before it resolves would show Login to an already-signed-in user for a frame.
  if (bootstrapping) {
    return (
      <View style={[styles.splash, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  const navTheme = scheme === 'dark' ? DarkTheme : DefaultTheme;

  return (
    <NavigationContainer theme={navTheme}>
      {token === null ? (
        <AuthStack.Navigator screenOptions={{ headerShown: false }}>
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
        <AppStack.Navigator screenOptions={{ headerShown: false }}>
          <AppStack.Screen name="Home" component={PlaceholderHome} />
        </AppStack.Navigator>
      )}
    </NavigationContainer>
  );
}

// Stands in for the bottom-tab navigator that Phase 2 introduces (Dashboard, Ledger, Import,
// Insights, More). Present only so the signed-in-and-verified branch renders something real.
function PlaceholderHome() {
  const c = useTheme();
  const { email, logout } = useAuth();
  return (
    <View style={[styles.splash, { backgroundColor: c.bg }]}>
      <Text style={[styles.placeholderTitle, { color: c.ink }]}>Signed in</Text>
      <Text style={[styles.placeholderBody, { color: c.muted }]}>{email}</Text>
      <Text style={[styles.placeholderBody, { color: c.muted }]}>
        Phone verified. Dashboard and the rest of the app arrive in Phase 2.
      </Text>
      <View style={styles.placeholderAction}>
        <Text style={[styles.signOut, { color: c.primary }]} onPress={logout}>
          Sign out
        </Text>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  splash: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: 24,
  },
  placeholderTitle: {
    fontSize: 22,
    fontWeight: '700',
    marginBottom: 8,
  },
  placeholderBody: {
    fontSize: 13,
    textAlign: 'center',
    marginBottom: 4,
  },
  placeholderAction: {
    marginTop: 24,
  },
  signOut: {
    fontSize: 14,
    fontWeight: '600',
  },
});
