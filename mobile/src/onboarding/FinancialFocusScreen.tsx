import { useState } from 'react';
import { Pressable, ScrollView, Text, View, StyleSheet } from 'react-native';
import { Button } from '../components/Button';
import { useTheme } from '../theme';

const OPTIONS: { key: string; label: string }[] = [
  { key: 'TRACK_SPENDING', label: '💰 Track my spending' },
  { key: 'MANAGE_BUDGETS', label: '📊 Create and manage budgets' },
  { key: 'SAVE_FOR_GOAL', label: '🎯 Save for a goal' },
  { key: 'SEE_ALL_ACCOUNTS', label: '🏦 See all my accounts in one place' },
  { key: 'IMPROVE_HABITS', label: '📈 Improve my financial habits' },
  { key: 'REDUCE_DEBT', label: '💳 Reduce debt' },
  { key: 'EXPLORING', label: '🔍 Just exploring' },
];

interface Props {
  onContinue: (selected: string[]) => void;
}

export function FinancialFocusScreen({ onContinue }: Props) {
  const c = useTheme();
  const [selected, setSelected] = useState<string[]>([]);

  function toggle(key: string) {
    if (key === 'EXPLORING') {
      setSelected((prev) => (prev.includes('EXPLORING') ? [] : ['EXPLORING']));
      return;
    }
    setSelected((prev) => {
      const withoutExploring = prev.filter((k) => k !== 'EXPLORING');
      return withoutExploring.includes(key)
        ? withoutExploring.filter((k) => k !== key)
        : [...withoutExploring, key];
    });
  }

  return (
    <ScrollView contentContainerStyle={[styles.container, { backgroundColor: c.bg }]}>
      <Text style={[styles.title, { color: c.ink }]}>What would you like to achieve with Fynora?</Text>
      <Text style={[styles.subtitle, { color: c.muted }]}>Select all that apply. We'll personalize your experience.</Text>
      {OPTIONS.map((opt) => {
        const active = selected.includes(opt.key);
        return (
          <Pressable accessibilityRole="button"
            key={opt.key}
            onPress={() => toggle(opt.key)}
            style={[styles.option, { borderColor: active ? c.primary : c.border }]}
          >
            <Text style={{ color: c.ink }}>{opt.label}</Text>
          </Pressable>
        );
      })}
      <View style={{ height: 16 }} />
      <Button label="Continue" onPress={() => onContinue(selected)} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: { flexGrow: 1, alignItems: 'stretch', padding: 24 },
  title: { fontSize: 22, fontWeight: '700', marginBottom: 8, textAlign: 'center' },
  subtitle: { fontSize: 13, textAlign: 'center', marginBottom: 20 },
  option: { borderWidth: 1, borderRadius: 10, padding: 14, marginBottom: 10 },
});
