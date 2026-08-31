import { FlatList, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { hapticSelection } from '../lib/haptics';
import { radius, spacing, useTheme } from '../theme';

/**
 * Replaces the web's inline `<select>` wherever one appears -- the import review's category
 * dropdown, the budget form's category, the reports month picker. A native picker sheet is the
 * equivalent affordance: a dropdown inside a scrolling list is fiddly to hit and easy to change by
 * accident while scrolling.
 */
interface Props {
  visible: boolean;
  title: string;
  options: string[];
  selected: string | null;
  onSelect: (option: string) => void;
  onClose: () => void;
}

export function OptionPickerModal({ visible, title, options, selected, onSelect, onClose }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel={`Close ${title} picker`} />
      <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
        <View style={styles.header}>
          <Text style={[styles.title, { color: c.ink }]}>{title}</Text>
          <Pressable onPress={onClose} hitSlop={12} accessibilityRole="button">
            <Text style={[styles.done, { color: c.primary }]}>Done</Text>
          </Pressable>
        </View>

        <FlatList
          data={options}
          keyExtractor={(item) => item}
          style={styles.list}
          renderItem={({ item }) => {
            const isSelected = item === selected;
            return (
              <Pressable
                onPress={() => {
                  hapticSelection();
                  onSelect(item);
                }}
                accessibilityRole="button"
                accessibilityState={{ selected: isSelected }}
                style={[styles.option, { borderBottomColor: c.border }]}
                android_ripple={{ color: c.border }}
              >
                <Text style={[styles.optionText, { color: isSelected ? c.primary : c.ink }]}>{item}</Text>
                {isSelected ? <Text style={[styles.check, { color: c.primary }]}>✓</Text> : null}
              </Pressable>
            );
          }}
        />
      </View>
    </Modal>
  );
}

const ROW_HEIGHT = 48;

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.35)' },
  sheet: {
    maxHeight: '70%',
    borderTopLeftRadius: radius.xl,
    borderTopRightRadius: radius.xl,
    paddingHorizontal: spacing.md,
    paddingTop: spacing.md,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.sm,
  },
  title: { fontSize: 17, fontWeight: '700' },
  done: { fontSize: 15, fontWeight: '600' },
  list: { flexGrow: 0 },
  option: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    height: ROW_HEIGHT,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  optionText: { fontSize: 15 },
  check: { fontSize: 16, fontWeight: '700' },
});
