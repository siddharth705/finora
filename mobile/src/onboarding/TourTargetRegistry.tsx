import { createContext, useCallback, useContext, useState, type ReactNode } from 'react';
import type { View } from 'react-native';

// Two contexts, not one, and this is load-bearing, not stylistic: a single context whose value
// object bundles `register` alongside `targets` gives `register` a new identity every time ANY
// target registers (the value object itself is new on every re-render triggered by a
// registration). useRegisterTourTarget's useCallback then depends on that unstable identity, so
// the ref callback it hands back to <View ref={register}> changes on every render too -- and
// React re-invokes a changed ref callback (null, then the new node) on every commit, which
// re-registers, which re-renders, forever. Splitting `register` into its own context (created
// once, stable for the provider's whole lifetime) keeps ref-callback identity stable across
// registrations; only `useTourTarget`'s read side needs to react to `targets` changing.
const RegisterContext = createContext<((key: string, node: View | null) => void) | null>(null);
const TargetsContext = createContext<Record<string, View | null> | null>(null);

export function TourTargetProvider({ children }: { children: ReactNode }) {
  const [targets, setTargets] = useState<Record<string, View | null>>({});
  const register = useCallback((key: string, node: View | null) => {
    setTargets((prev) => (prev[key] === node ? prev : { ...prev, [key]: node }));
  }, []);

  return (
    <RegisterContext.Provider value={register}>
      <TargetsContext.Provider value={targets}>
        {children}
      </TargetsContext.Provider>
    </RegisterContext.Provider>
  );
}

export function useRegisterTourTarget(key: string) {
  const register = useContext(RegisterContext);
  if (!register) throw new Error('useRegisterTourTarget must be used within TourTargetProvider');
  return useCallback((node: View | null) => register(key, node), [register, key]);
}

export function useTourTarget(key: string): View | null {
  const targets = useContext(TargetsContext);
  if (!targets) throw new Error('useTourTarget must be used within TourTargetProvider');
  return targets[key] ?? null;
}
