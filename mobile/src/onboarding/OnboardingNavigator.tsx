import { View, Text } from 'react-native';
import { TourTargetProvider } from './TourTargetRegistry';

// Full screens land in Task 15/16. This stub exists so RootNavigator has something real to mount
// and test against in this task.
export function OnboardingNavigator() {
  return (
    <TourTargetProvider>
      <View testID="onboarding-navigator"><Text>Onboarding placeholder</Text></View>
    </TourTargetProvider>
  );
}
