import { useEffect } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { queryClient, startNetworkMonitoring } from './src/api/queryClient';
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

// AuthProvider sits inside QueryClientProvider because auth calls go through the same API client
// every query does, and outside RootNavigator because the navigator picks its stack from auth
// state (see RootNavigator's own comment). OfflineBoundary wraps the navigator rather than living
// inside it so the banner spans every screen including the auth stack -- a failed sign-in with no
// connection is exactly when the explanation matters most.
function App() {
  // Subscribing here rather than at module scope keeps the NetInfo listener tied to the app's
  // lifetime and torn down cleanly, instead of leaking across fast-refresh reloads in development.
  useEffect(() => startNetworkMonitoring(), []);

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
