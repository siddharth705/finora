import { Text, View, StyleSheet } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import { Card } from '../components/Card';
import { onboardingApi } from '../api/endpoints';
import { useTheme } from '../theme';
import { CHECKLIST_ITEMS } from './checklistItems';

export function ChecklistWidget() {
  const c = useTheme();
  const { data } = useQuery({ queryKey: ['onboarding', 'checklist'], queryFn: onboardingApi.getChecklist });

  if (!data || data.completedCount >= data.totalCount) return null;

  const completedKeys = new Set(data.items.filter((i) => i.completed).map((i) => i.key));
  const percent = Math.round((data.completedCount / data.totalCount) * 100);

  return (
    <Card style={styles.card}>
      <Text style={[styles.title, { color: c.ink }]}>Getting Started</Text>
      <Text style={[styles.progress, { color: c.muted }]}>{data.completedCount} of {data.totalCount} completed</Text>
      <View style={[styles.track, { backgroundColor: c.border }]}>
        <View style={[styles.fill, { backgroundColor: c.primary, width: `${percent}%` }]} />
      </View>
      {CHECKLIST_ITEMS.map((item) => (
        <View key={item.key} style={styles.row}>
          <Text style={{ color: c.ink }}>{completedKeys.has(item.key) ? '✅' : '⬜'}</Text>
          <Text style={[styles.label, { color: c.muted }]}>{item.label}</Text>
        </View>
      ))}
    </Card>
  );
}

const styles = StyleSheet.create({
  card: { marginBottom: 16, padding: 16 },
  title: { fontSize: 15, fontWeight: '600', marginBottom: 4 },
  progress: { fontSize: 12, marginBottom: 12 },
  track: { height: 6, borderRadius: 3, overflow: 'hidden', marginBottom: 12 },
  fill: { height: '100%' },
  row: { flexDirection: 'row', alignItems: 'center', gap: 8, marginBottom: 6 },
  label: { fontSize: 14 },
});
