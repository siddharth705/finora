import { useEffect } from 'react';
import { QueryClientProvider } from '@tanstack/react-query';
import { StatusBar } from 'expo-status-bar';
import { SafeAreaProvider } from 'react-native-safe-area-context';
import { queryClient, startNetworkMonitoring } from './src/api/queryClient';
import { OfflineBoundary } from './src/components/OfflineBanner';
import { AuthProvider } from './src/context/AuthContext';
import { RootNavigator } from './src/navigation/RootNavigator';

// AuthProvider sits inside QueryClientProvider because auth calls go through the same API client
// every query does, and outside RootNavigator because the navigator picks its stack from auth
// state (see RootNavigator's own comment). OfflineBoundary wraps the navigator rather than living
// inside it so the banner spans every screen including the auth stack -- a failed sign-in with no
// connection is exactly when the explanation matters most.
export default function App() {
  // Subscribing here rather than at module scope keeps the NetInfo listener tied to the app's
  // lifetime and torn down cleanly, instead of leaking across fast-refresh reloads in development.
  useEffect(() => startNetworkMonitoring(), []);

  return (
    <QueryClientProvider client={queryClient}>
      <SafeAreaProvider>
        <AuthProvider>
          <OfflineBoundary>
            <RootNavigator />
          </OfflineBoundary>
          <StatusBar style="auto" />
        </AuthProvider>
      </SafeAreaProvider>
    </QueryClientProvider>
  );
}
