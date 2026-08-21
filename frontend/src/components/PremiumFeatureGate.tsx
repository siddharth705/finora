import type { ReactNode } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Lock } from 'lucide-react';
import { entitlementsApi } from '../api/endpoints';

interface PremiumFeatureGateProps {
  /** One of FeatureEntitlement's backend constants (e.g. "FINO_AI", "ADVANCED_REPORTS"). */
  featureKey: string;
  children: ReactNode;
  /** Custom UI for the locked state. Defaults to a generic upgrade prompt. */
  fallback?: ReactNode;
}

/**
 * D-28 PR4-A. Gates a piece of UI behind a feature entitlement. Fails closed while the
 * entitlements query is loading or has errored -- same "no access by default" contract as the
 * backend's own EntitlementService.hasEntitlement -- so a slow or failed request never briefly
 * reveals a feature it shouldn't.
 */
export function PremiumFeatureGate({ featureKey, children, fallback }: PremiumFeatureGateProps) {
  const { data, isLoading, isError } = useQuery({
    queryKey: ['entitlements'],
    queryFn: () => entitlementsApi.mine(),
  });

  if (isLoading || isError) return null;

  const hasAccess = data?.features[featureKey] === true;
  if (hasAccess) return <>{children}</>;
  return fallback !== undefined ? <>{fallback}</> : <DefaultUpgradePrompt />;
}

function DefaultUpgradePrompt() {
  return (
    <div className="flex flex-col items-center text-center py-6 px-4 bg-bg rounded-xl2 border border-border">
      <div className="w-10 h-10 rounded-full bg-primary-light flex items-center justify-center mb-3">
        <Lock size={16} className="text-primary" />
      </div>
      <p className="text-sm font-semibold text-ink mb-1">This is a premium feature</p>
      <p className="text-xs text-muted">Upgrade your plan to unlock this.</p>
    </div>
  );
}
