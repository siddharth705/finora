import type { ReactNode } from 'react';
import { View, Text, StyleSheet } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import Ionicons from '@expo/vector-icons/Ionicons';
import { entitlementsApi } from '../api/endpoints';
import { useTheme } from '../theme';

interface PremiumFeatureGateProps {
  /** One of FeatureEntitlement's backend constants (e.g. "FINO_AI", "ADVANCED_REPORTS"). */
  featureKey: string;
  children: ReactNode;
  fallback?: ReactNode;
}

/** Ported from frontend/src/components/PremiumFeatureGate.tsx -- same fail-closed contract (no
 *  access while loading or on error), adapted to React Native primitives. Mobile had no
 *  entitlement-gating code at all before this (design spec §8's own correction). */
export function PremiumFeatureGate({ featureKey, children, fallback }: PremiumFeatureGateProps) {
  const c = useTheme();
  const { data, isLoading, isError } = useQuery({
    queryKey: ['entitlements'],
    queryFn: () => entitlementsApi.mine(),
  });

  if (isLoading || isError) return null;

  const hasAccess = data?.features[featureKey] === true;
  if (hasAccess) return <>{children}</>;
  if (fallback !== undefined) return <>{fallback}</>;

  return (
    <View style={[styles.container, { backgroundColor: c.bg, borderColor: c.border }]}>
      <View style={[styles.iconCircle, { backgroundColor: c.primaryLight }]}>
        <Ionicons name="lock-closed-outline" size={16} color={c.primary} />
      </View>
      <Text style={[styles.title, { color: c.ink }]}>This is a premium feature</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>Upgrade your plan to unlock this.</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  container: { alignItems: 'center', paddingVertical: 24, paddingHorizontal: 16, borderRadius: 16, borderWidth: 1 },
  iconCircle: { width: 40, height: 40, borderRadius: 20, alignItems: 'center', justifyContent: 'center', marginBottom: 12 },
  title: { fontSize: 14, fontWeight: '600', marginBottom: 4 },
  subtitle: { fontSize: 12 },
});
