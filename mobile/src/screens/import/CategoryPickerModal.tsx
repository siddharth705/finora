import { FlatList, Modal, Pressable, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { radius, spacing, useTheme } from '../../theme';

/**
 * Replaces the web review table's inline `<select>`. A native picker sheet is the equivalent
 * affordance -- a dropdown inside a scrolling list of cards is fiddly to hit and easy to change by
 * accident while scrolling.
 */
interface Props {
  visible: boolean;
  categories: string[];
  selected: string | null;
  onSelect: (category: string) => void;
  onClose: () => void;
}

export function CategoryPickerModal({ visible, categories, selected, onSelect, onClose }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <Pressable style={styles.backdrop} onPress={onClose} accessibilityLabel="Close category picker" />
      <View style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}>
        <View style={styles.header}>
          <Text style={[styles.title, { color: c.ink }]}>Category</Text>
          <Pressable onPress={onClose} hitSlop={12} accessibilityRole="button">
            <Text style={[styles.done, { color: c.primary }]}>Done</Text>
          </Pressable>
        </View>

        <FlatList
          data={categories}
          keyExtractor={(item) => item}
          style={styles.list}
          renderItem={({ item }) => {
            const isSelected = item === selected;
            return (
              <Pressable
                onPress={() => onSelect(item)}
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
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  optionText: { fontSize: 15 },
  check: { fontSize: 16, fontWeight: '700' },
});
