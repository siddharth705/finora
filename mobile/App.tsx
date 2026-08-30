import { useEffect } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { StatusBar } from 'expo-status-bar';
import * as SplashScreen from 'expo-splash-screen';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { queryClient, startNetworkMonitoring, startQueryPersistence } from './src/api/queryClient';
import { AppLockGate } from './src/components/AppLockGate';
import { OfflineBoundary } from './src/components/OfflineBanner';
import { RootWarningBoundary } from './src/components/RootWarningBanner';
import { AuthProvider } from './src/context/AuthContext';
import { initMonitoring, withMonitoring } from './src/lib/monitoring';
import { RootNavigator } from './src/navigation/RootNavigator';
import { ThemeProvider } from './src/theme';

// Before the component, not inside an effect: an error thrown during the first render is exactly
// the kind worth capturing, and by the time an effect runs it would already be too late. No-ops
// when EXPO_PUBLIC_SENTRY_DSN is unset -- see src/lib/monitoring.ts.
initMonitoring();

// Also before the component: the native splash screen otherwise auto-hides itself as soon as the
// first frame is handed to the native renderer, which can land before this module has even
// finished evaluating (this file, everything it imports, RootNavigator's own tree). That gap
// between "native splash gone" and "first React frame actually painted" is a blank white/black
// flash on cold start. Held off until the mount effect below explicitly releases it, once React
// has committed a real frame -- RootNavigator's own bootstrapping spinner (auth restore) is what
// the user sees after that, not before it.
void SplashScreen.preventAutoHideAsync();

// AuthProvider sits inside QueryClientProvider because auth calls go through the same API client
// every query does, and outside RootNavigator because the navigator picks its stack from auth
// state (see RootNavigator's own comment). OfflineBoundary wraps the navigator rather than living
// inside it so the banner spans every screen including the auth stack -- a failed sign-in with no
// connection is exactly when the explanation matters most.
function App() {
  // Subscribing here rather than at module scope keeps the NetInfo listener tied to the app's
  // lifetime and torn down cleanly, instead of leaking across fast-refresh reloads in development.
  useEffect(() => startNetworkMonitoring(), []);
  // Warms the query cache from AsyncStorage on cold start and keeps saving it as it changes -- see
  // startQueryPersistence's own doc comment in api/queryClient.ts. Same posture as the
  // network-monitoring effect just above: subscribed here, not at module scope, so it's torn down
  // cleanly rather than leaking across fast-refresh reloads in development.
  useEffect(() => startQueryPersistence(), []);

  // Fires after this render has committed, i.e. after RootNavigator's tree (its own bootstrapping
  // spinner, at minimum) has something real to paint -- see the preventAutoHideAsync comment
  // above for why this can't just be the native default. Deliberately NOT gated on auth
  // bootstrapping finishing: waiting that long would hold the native splash up through the whole
  // SecureStore restore with no visible progress, which is worse than handing off to the spinner
  // RootNavigator already shows for exactly that wait.
  useEffect(() => {
    void SplashScreen.hideAsync();
  }, []);

  return (
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider>
        {/* Inside AuthProvider is tempting but wrong: the provider reads the account's saved theme
            itself from storage, and sitting outside means the choice is already applied to the auth
            screens a signed-out user sees. */}
        <ThemeProvider>
          {/* SEC-08: outside AuthProvider, deliberately -- a rooted/jailbroken device is a
              concern regardless of sign-in state, so this spans the auth stack too, the same
              reason OfflineBoundary does. */}
          <RootWarningBoundary>
            <AuthProvider>
              {/* SEC-09: inside AuthProvider (needs useAuth()'s token/logout), outside/around
                  RootNavigator so a locked session replaces the entire app UI, not just one screen
                  inside it -- see AppLockGate's own doc comment for when it actually engages. */}
              <AppLockGate>
                <OfflineBoundary>
                  <RootNavigator />
                </OfflineBoundary>
              </AppLockGate>
              <StatusBar style="auto" />
            </AuthProvider>
          </RootWarningBoundary>
        </ThemeProvider>
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}

// Catches native crashes and unhandled JS errors that never reach a component's own error handling.
export default withMonitoring(App);
