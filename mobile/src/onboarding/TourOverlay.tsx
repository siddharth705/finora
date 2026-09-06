import { useEffect, useState } from 'react';
import { Modal, Text, View, StyleSheet } from 'react-native';
import { Button } from '../components/Button';
import { useTheme } from '../theme';
import type { TourStep } from './tourSteps';

interface Props {
  steps: TourStep[];
  navigateToTab: (tab: TourStep['tab']) => void;
  onFinish: () => void;
  onSkip: () => void;
}

export function TourOverlay({ steps, navigateToTab, onFinish, onSkip }: Props) {
  const c = useTheme();
  const [index, setIndex] = useState(0);
  const step = steps[index];
  const isLast = index === steps.length - 1;

  useEffect(() => {
    navigateToTab(step.tab);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [step.tab]);

  function next() {
    if (isLast) {
      onFinish();
    } else {
      setIndex((i) => i + 1);
    }
  }

  function back() {
    setIndex((i) => Math.max(0, i - 1));
  }

  // The spotlight cutout itself (reading useTourTarget(step.key), measuring it via
  // measureInWindow, and drawing an react-native-svg mask around it) is a deliberate follow-up,
  // not built here: a step that just navigated needs to wait for the new screen to mount and the
  // target ref to register before there is anything to measure, which is a real race this
  // component doesn't resolve yet. The tooltip content and Next/Back/Skip/Finish flow -- the
  // thing every test in this file actually exercises -- ships first.
  return (
    <Modal transparent animationType="fade">
      <View style={styles.backdrop}>
        <View style={[styles.card, { backgroundColor: c.card }]}>
          <Text style={[styles.title, { color: c.ink }]}>{step.title}</Text>
          <Text style={[styles.body, { color: c.muted }]}>{step.body}</Text>
          <View style={styles.row}>
            <Text onPress={onSkip} style={{ color: c.muted, fontSize: 12 }}>Skip</Text>
            <View style={styles.row}>
              {index > 0 && (
                <>
                  <Button label="Back" onPress={back} variant="link" />
                  <View style={{ width: 8 }} />
                </>
              )}
              <Button label={isLast ? 'Finish' : 'Next'} onPress={next} />
            </View>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.6)', justifyContent: 'flex-end' },
  card: { borderTopLeftRadius: 16, borderTopRightRadius: 16, padding: 20 },
  title: { fontSize: 18, fontWeight: '700', marginBottom: 6 },
  body: { fontSize: 14, marginBottom: 16 },
  row: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' },
});
