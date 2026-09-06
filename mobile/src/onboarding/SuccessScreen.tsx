import { ScrollView, Text, View, StyleSheet } from 'react-native';
import { Button } from '../components/Button';
import { useTheme } from '../theme';
import { CHECKLIST_ITEMS } from './checklistItems';

interface Props {
  onDone: () => void;
}

// Import Statement/Connect Account/Go to Dashboard all call onDone: once onDone fires,
// RootNavigator unmounts OnboardingNavigator and mounts AppTabs fresh at its default Home tab --
// there is no navigator instance yet to imperatively route within from inside OnboardingNavigator.
// A deliberate, accepted v1 scope decision (see the implementation plan's own note on this), not
// a guess -- distinct destinations would need a documented follow-up (a pending-navigation-target
// ref consumed by AppTabs on mount), which would be exactly the unrequested scope the design
// spec's §9 already rules out.
export function SuccessScreen({ onDone }: Props) {
  const c = useTheme();
  return (
    <ScrollView contentContainerStyle={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.title, { color: c.ink }]}>You're Ready to Go 🚀</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>
        Start by importing your first bank statement or connecting an account. The more data you
        add, the smarter Fynora becomes.
      </Text>
      <View style={styles.checklist}>
        <Text style={[styles.checklistTitle, { color: c.ink }]}>Next steps:</Text>
        {CHECKLIST_ITEMS.map((item) => (
          <Text key={item.key} style={{ color: c.muted, marginBottom: 4 }}>☐ {item.label}</Text>
        ))}
      </View>
      <Button label="Import Statement" onPress={onDone} />
      <View style={{ height: 8 }} />
      <Button label="Connect Account" onPress={onDone} variant="link" />
      <View style={{ height: 8 }} />
      <Button label="Go to Dashboard" onPress={onDone} variant="link" />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flexGrow: 1, alignItems: 'center', padding: 24, justifyContent: 'center' },
  title: { fontSize: 26, fontWeight: '700', marginBottom: 12, textAlign: 'center' },
  subtitle: { fontSize: 14, textAlign: 'center', marginBottom: 20 },
  checklist: { alignSelf: 'stretch', marginBottom: 24 },
  checklistTitle: { fontWeight: '600', marginBottom: 8 },
});
