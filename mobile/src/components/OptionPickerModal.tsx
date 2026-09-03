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
      {/* Hidden from assistive tech deliberately. As the Modal's first child it was taking initial
          VoiceOver focus, so opening the sheet announced "Close ... picker" instead of the picker,
          and a single double-tap at that landing spot dismissed the sheet the user had just
          opened. It is a pointer-only convenience -- "Done" and the Android back button are the
          accessible ways out -- so it should not be an element in the AT tree at all. */}
      <Pressable
        style={styles.backdrop}
        onPress={onClose}
        accessible={false}
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
      />
      <View
        style={[styles.sheet, { backgroundColor: c.card, paddingBottom: insets.bottom + spacing.md }]}
        accessibilityViewIsModal
      >
        <View style={styles.header}>
          <Text style={[styles.title, { color: c.ink }]} accessibilityRole="header">{title}</Text>
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
                {/* Decorative: accessibilityState.selected above already conveys this, so without
                    hiding it the row announces "Groceries, check mark, selected" -- the same
                    treatment the Dashboard nudge's chevron gets. */}
                {isSelected ? (
                  <Text
                    style={[styles.check, { color: c.primary }]}
                    accessibilityElementsHidden
                    importantForAccessibility="no"
                  >
                    ✓
                  </Text>
                ) : null}
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
  // flexShrink:1 is load-bearing, not tidiness. Yoga defaults flexShrink to 0 (web CSS defaults
  // it to 1), so with flexGrow:0 alone this FlatList was laid out at its FULL content height and
  // merely clipped by the sheet's maxHeight above -- and because a ScrollView's frame then equals
  // its content size, there was nothing left to scroll. Any list taller than 70% of the screen
  // (the timezone picker in Settings; ~12+ categories on a small device) simply had its tail
  // permanently unreachable. flexShrink:1 lets Yoga compress the list into the space the cap
  // leaves, which is what makes it scroll; it is inert when the content already fits, so the
  // short-list case is unchanged.
  list: { flexGrow: 0, flexShrink: 1 },
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
