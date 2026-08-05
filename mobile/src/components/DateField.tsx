import { useState } from 'react';
import { Platform, Pressable, StyleSheet, Text, View } from 'react-native';
import DateTimePicker, { DateTimePickerAndroid } from '@react-native-community/datetimepicker';
import { fmtDate, fromLocalDateString, toLocalDateString } from '../lib/format';
import { radius, spacing, useTheme } from '../theme';

/**
 * Replaces the web forms' `<input type="date">`, which React Native has no equivalent of.
 *
 * The system picker rather than a hand-rolled one, unlike the charts in this app: a date picker is
 * the one control users already know from every other app on their phone, and typing "2027-03-15"
 * on a phone keyboard is both slower and easy to get wrong. It costs no new build workflow -- this
 * app already requires a dev client for Firebase phone auth, so a native module was always assumed.
 *
 * The two platforms genuinely differ in how the picker is presented -- Android opens an imperative
 * dialog like Alert, iOS renders a component -- so that split lives here rather than in each form.
 *
 * `value`/`onChange` speak the backend's "YYYY-MM-DD" LocalDate strings, never Date objects, and
 * the conversion goes through format.ts's local-calendar helpers so the day can't shift by one.
 */
interface Props {
  label: string;
  /** "YYYY-MM-DD", or null for "not set". */
  value: string | null;
  onChange: (value: string | null) => void;
  /** Shown when nothing is picked yet. */
  placeholder?: string;
  minimumDate?: Date;
}

export function DateField({ label, value, onChange, placeholder = 'Not set', minimumDate }: Props) {
  const c = useTheme();
  const [iosOpen, setIosOpen] = useState(false);
  const current = value ? fromLocalDateString(value) : new Date();

  function toggle() {
    if (Platform.OS === 'android') {
      DateTimePickerAndroid.open({
        value: current,
        mode: 'date',
        minimumDate,
        onChange: (event, date) => {
          // 'dismissed' fires with no date when the user backs out -- treating that as a change
          // would silently set the goal's date to today.
          if (event.type === 'set' && date) onChange(toLocalDateString(date));
        },
      });
      return;
    }
    // iOS renders the calendar inline, and an inline picker has no cancel button of its own -- so
    // without this toggle, opening it leaves picking a date as the only way to close it again.
    // Android's dialog brings its own dismissal, hence the early return above.
    setIosOpen((open) => !open);
  }

  return (
    <View style={styles.wrap}>
      <Text style={[styles.label, { color: c.muted }]}>{label}</Text>
      <View style={styles.row}>
        <Pressable
          onPress={toggle}
          style={[styles.field, { backgroundColor: c.inputBg, borderColor: c.border }]}
          accessibilityRole="button"
          accessibilityState={{ expanded: iosOpen }}
          accessibilityLabel={value ? `${label}: ${fmtDate(value)}. Change` : `${label}: not set. Choose a date`}
        >
          <Text style={[styles.value, { color: value ? c.ink : c.muted }]}>
            {value ? fmtDate(value) : placeholder}
          </Text>
        </Pressable>
        {value ? (
          <Pressable onPress={() => onChange(null)} hitSlop={8} accessibilityRole="button" accessibilityLabel={`Clear ${label}`}>
            <Text style={[styles.clear, { color: c.primary }]}>Clear</Text>
          </Pressable>
        ) : null}
      </View>

      {iosOpen ? (
        <DateTimePicker
          value={current}
          mode="date"
          display="inline"
          minimumDate={minimumDate}
          onChange={(event, date) => {
            setIosOpen(false);
            if (event.type === 'set' && date) onChange(toLocalDateString(date));
          }}
        />
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  wrap: { marginBottom: spacing.xs },
  label: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  row: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  field: {
    flex: 1,
    justifyContent: 'center',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    minHeight: 48,
  },
  value: { fontSize: 15 },
  clear: { fontSize: 13, fontWeight: '600' },
});
