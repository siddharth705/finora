import { useState } from 'react';
import {
  ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useQueries, useQueryClient } from '@tanstack/react-query';
import { MetricTile, SaveStatus, SectionCard, VerifiedBadge } from '../components/AccountUI';
import { Button } from '../components/Button';
import { OptionPickerModal } from '../components/OptionPickerModal';
import { TextField } from '../components/TextField';
import { ChangePasswordSheet } from './settings/ChangePasswordSheet';
import { DeviceSessionsSection } from './settings/DeviceSessionsSection';
import { analyticsApi, userApi, workspaceApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtDate, fmtRelativeTime } from '../lib/format';
import { maskPhone } from '../lib/maskPhone';
import { useSingleFlight } from '../lib/useSingleFlight';
import { useTransientFlag } from '../lib/useTransientFlag';
import { parsePositiveAmount } from '../lib/validation';
import { radius, spacing, THEME_SETTINGS, useTheme, useThemeSetting, type ThemeSetting } from '../theme';

/**
 * Port of frontend/src/pages/Settings.tsx -- "how Finora behaves for you", as opposed to
 * ProfileScreen's "who you are".
 *
 * Same capabilities-first scope as the web page, and worth restating because it is the easiest
 * discipline to lose: every section here reflects a real, backed setting or fact. No placeholder
 * rows for 2FA, API keys, integrations, notification preferences, storage usage or a
 * plan/subscription -- none of those exist on the backend, so none of them get a control. Add a
 * section the day the capability it configures ships, not before.
 */
const THEME_LABEL: Record<ThemeSetting, string> = {
  system: 'System',
  light: 'Light',
  dark: 'Dark',
};

/** Threshold moves in 5% steps -- fine-grained enough for a confidence cutoff, and it avoids
 *  pulling in a native slider dependency for one control. */
const THRESHOLD_STEP = 5;

/**
 * Falls back to a curated list where Intl.supportedValuesOf is unavailable, rather than leaving
 * the picker empty. Hermes ships full ICU on current React Native, so the full list is the normal
 * path; the fallback covers older engines.
 */
function availableTimezones(): string[] {
  try {
    const values = (Intl as { supportedValuesOf?: (k: string) => string[] }).supportedValuesOf?.('timeZone');
    if (Array.isArray(values) && values.length > 0) return values;
  } catch {
    // fall through
  }
  return [
    'Asia/Kolkata', 'UTC', 'America/New_York', 'America/Chicago', 'America/Denver',
    'America/Los_Angeles', 'Europe/London', 'Europe/Paris', 'Europe/Berlin', 'Asia/Dubai',
    'Asia/Singapore', 'Asia/Tokyo', 'Asia/Shanghai', 'Australia/Sydney',
  ];
}

export function SettingsScreen() {
  const c = useTheme();
  const { setting: themeSetting, setSetting: setThemeSetting } = useThemeSetting();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();

  // Each editable field is a DRAFT overlaying the server's value: null means "nothing typed yet,
  // follow the account", anything else is the user's edit. Seeding real state from the server in
  // an effect instead would render twice, leave two sources of truth for the same field, and
  // silently overwrite an in-progress edit whenever the query refetched.
  const [lowBalanceDraft, setLowBalanceDraft] = useState<string | null>(null);
  const [timezoneDraft, setTimezoneDraft] = useState<string | null>(null);
  const [thresholdDraft, setThresholdDraft] = useState<number | null>(null);
  const [timezonePickerOpen, setTimezonePickerOpen] = useState(false);
  const [prefsSaving, setPrefsSaving] = useState(false);
  const [prefsJustSaved, confirmPrefsSaved] = useTransientFlag();
  const [prefsError, setPrefsError] = useState<string | null>(null);

  const [intelSaving, setIntelSaving] = useState(false);
  const [intelJustSaved, confirmIntelSaved] = useTransientFlag();
  const [intelError, setIntelError] = useState<string | null>(null);

  const [changePasswordOpen, setChangePasswordOpen] = useState(false);

  const [userQ, workspaceQ, statsQ] = useQueries({
    queries: [
      { queryKey: ['user-settings'], queryFn: () => userApi.get() },
      { queryKey: ['workspace-settings'], queryFn: () => workspaceApi.getSettings() },
      // Best-effort: the Data section shows "—" for any stat that doesn't load rather than
      // blocking the rest of the screen on it.
      { queryKey: ['import-statistics'], queryFn: () => analyticsApi.importStatistics(), retry: false },
    ],
  });

  const user = userQ.data;
  const savedLowBalance = user ? String(user.lowBalanceThreshold) : '';
  const savedTimezone = user?.timezone ?? '';
  const savedThreshold = workspaceQ.data?.autoApplyConfidenceThreshold ?? 90;

  const lowBalance = lowBalanceDraft ?? savedLowBalance;
  const timezone = timezoneDraft ?? savedTimezone;
  const threshold = thresholdDraft ?? savedThreshold;

  const prefsDirty = lowBalance !== savedLowBalance || timezone !== savedTimezone;
  const intelDirty = threshold !== savedThreshold;

  async function savePreferences() {
    const amount = parsePositiveAmount(lowBalance);
    if (amount === null) {
      setPrefsError('Low balance alert must be a number greater than zero.');
      return;
    }
    setPrefsError(null);
    await singleFlight(async () => {
      setPrefsSaving(true);
      try {
        const updated = await userApi.update({ lowBalanceThreshold: amount, timezone });
        queryClient.setQueryData(['user-settings'], updated);
        // Drafts dropped so the fields follow the account again -- and so the form reflects any
        // normalization the server applied rather than the raw text that was typed.
        setLowBalanceDraft(null);
        setTimezoneDraft(null);
        // The Dashboard's greeting reads the timezone, and its notifications read the threshold.
        void queryClient.invalidateQueries({ queryKey: ['dashboard-summary'] });
        confirmPrefsSaved();
      } catch (e) {
        setPrefsError(toUserMessage(e, 'Could not save your preferences.'));
      } finally {
        setPrefsSaving(false);
      }
    });
  }

  async function saveThreshold() {
    setIntelError(null);
    await singleFlight(async () => {
      setIntelSaving(true);
      try {
        const saved = await workspaceApi.updateSettings({ autoApplyConfidenceThreshold: threshold });
        queryClient.setQueryData(['workspace-settings'], saved);
        setThresholdDraft(null);
        confirmIntelSaved();
      } catch (e) {
        setIntelError(toUserMessage(e, 'Could not save this setting.'));
      } finally {
        setIntelSaving(false);
      }
    });
  }

  function nudgeThreshold(delta: number) {
    setThresholdDraft((v) => Math.max(0, Math.min(100, (v ?? savedThreshold) + delta)));
  }

  if (userQ.isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  if (userQ.isError || !user) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.message, { color: c.muted }]}>
          Couldn&apos;t load your settings — please try again later.
        </Text>
      </View>
    );
  }

  const stats = statsQ.data;
  const passwordChanged = fmtRelativeTime(user.passwordChangedAt);

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
      refreshControl={
        <RefreshControl
          refreshing={userQ.isFetching && !userQ.isLoading}
          onRefresh={() => void userQ.refetch()}
          tintColor={c.primary}
        />
      }
    >
      <SectionCard title="General" subtitle="Customize how Finora works for you">
        <TextField
          label="Low balance alert"
          value={lowBalance}
          onChangeText={setLowBalanceDraft}
          keyboardType="decimal-pad"
          placeholder="2000"
        />

        <Text style={[styles.fieldLabel, { color: c.muted }]}>Timezone</Text>
        <Pressable
          onPress={() => setTimezonePickerOpen(true)}
          style={[styles.picker, { backgroundColor: c.inputBg, borderColor: c.border }]}
          accessibilityRole="button"
          accessibilityLabel={`Timezone: ${timezone || 'not set'}. Change`}
        >
          <Text style={[styles.pickerText, { color: c.ink }]} numberOfLines={1}>{timezone}</Text>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Pressable>

        <Text style={[styles.fieldLabel, { color: c.muted, marginTop: spacing.md }]}>Theme</Text>
        <View style={[styles.segments, { borderColor: c.border }]}>
          {THEME_SETTINGS.map((option) => {
            const active = themeSetting === option;
            return (
              <Pressable
                key={option}
                onPress={() => setThemeSetting(option)}
                style={[styles.segment, active && { backgroundColor: c.primaryLight }]}
                accessibilityRole="button"
                accessibilityState={{ selected: active }}
                accessibilityLabel={`${THEME_LABEL[option]} theme`}
              >
                <Text style={[styles.segmentText, { color: active ? c.primary : c.muted }]}>
                  {THEME_LABEL[option]}
                </Text>
              </Pressable>
            );
          })}
        </View>
        <Text style={[styles.hint, { color: c.muted }]}>
          Theme applies instantly. The alert amount and timezone save when you tap Save.
        </Text>

        {prefsError ? <Text style={[styles.error, { color: c.danger }]}>{prefsError}</Text> : null}
        <View style={styles.saveRow}>
          <SaveStatus dirty={prefsDirty} saving={prefsSaving} justSaved={prefsJustSaved} error={false} />
        </View>
        <Button
          label={prefsSaving ? 'Saving…' : 'Save preferences'}
          onPress={() => void savePreferences()}
          loading={prefsSaving}
          disabled={!prefsDirty}
        />
      </SectionCard>

      <SectionCard title="Security" subtitle="Your password, verification and active sessions">
        <View style={[styles.row, { borderBottomColor: c.border }]}>
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>Password</Text>
            <Text style={[styles.rowMeta, { color: c.muted }]}>
              {passwordChanged ? `Last changed ${passwordChanged}` : 'Never changed'}
            </Text>
          </View>
        </View>
        <View style={styles.changePassword}>
          <Button label="Change Password" onPress={() => setChangePasswordOpen(true)} />
        </View>

        <View style={[styles.row, { borderBottomColor: c.border }]}>
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>Phone verification</Text>
            <Text style={[styles.rowMeta, { color: c.muted }]}>
              {user.phoneNumber ? maskPhone(user.phoneNumber) : 'No phone number on file'}
            </Text>
          </View>
          {user.phoneVerified ? (
            <VerifiedBadge />
          ) : (
            <Text style={[styles.rowMeta, { color: c.muted }]}>Not verified</Text>
          )}
        </View>

        <View style={styles.sessions}>
          <DeviceSessionsSection />
        </View>
      </SectionCard>

      <SectionCard title="Categorization" subtitle="How confident a suggestion must be to apply on its own">
        {workspaceQ.isLoading ? (
          <ActivityIndicator color={c.primary} />
        ) : (
          <>
            {/* An "adjustable" with increment/decrement actions is React Native's equivalent of the
                web's range input -- a screen reader announces the value and offers swipe up/down to
                change it, which a pair of plain buttons would not. */}
            <View
              style={styles.stepper}
              accessible
              accessibilityRole="adjustable"
              accessibilityLabel="Confidence threshold"
              accessibilityValue={{ min: 0, max: 100, now: threshold, text: `${threshold} percent` }}
              accessibilityActions={[{ name: 'increment' }, { name: 'decrement' }]}
              onAccessibilityAction={(e) => {
                if (e.nativeEvent.actionName === 'increment') nudgeThreshold(THRESHOLD_STEP);
                if (e.nativeEvent.actionName === 'decrement') nudgeThreshold(-THRESHOLD_STEP);
              }}
            >
              <Pressable
                onPress={() => nudgeThreshold(-THRESHOLD_STEP)}
                disabled={threshold <= 0}
                style={[styles.stepButton, { borderColor: c.border }, threshold <= 0 && styles.disabled]}
                accessibilityRole="button"
                accessibilityLabel="Decrease threshold"
              >
                <Text style={[styles.stepButtonText, { color: c.ink }]}>−</Text>
              </Pressable>
              <Text style={[styles.stepValue, { color: c.ink }]}>{threshold}%</Text>
              <Pressable
                onPress={() => nudgeThreshold(THRESHOLD_STEP)}
                disabled={threshold >= 100}
                style={[styles.stepButton, { borderColor: c.border }, threshold >= 100 && styles.disabled]}
                accessibilityRole="button"
                accessibilityLabel="Increase threshold"
              >
                <Text style={[styles.stepButtonText, { color: c.ink }]}>+</Text>
              </Pressable>
            </View>
            <Text style={[styles.hint, { color: c.muted }]}>
              Suggestions at or above this confidence are applied automatically. Anything below it
              is left for you to confirm.
            </Text>

            {intelError ? <Text style={[styles.error, { color: c.danger }]}>{intelError}</Text> : null}
            <View style={styles.saveRow}>
              <SaveStatus dirty={intelDirty} saving={intelSaving} justSaved={intelJustSaved} error={false} />
            </View>
            <Button
              label={intelSaving ? 'Saving…' : 'Save setting'}
              onPress={() => void saveThreshold()}
              loading={intelSaving}
              disabled={!intelDirty}
            />
          </>
        )}
      </SectionCard>

      <SectionCard title="Data" subtitle="Your imported statements and transaction history">
        <View style={styles.tiles}>
          <MetricTile
            label="Statements"
            value={stats ? stats.totalStatements.toLocaleString('en-IN') : '—'}
          />
          <MetricTile
            label="Transactions"
            value={stats ? stats.totalTransactionsImported.toLocaleString('en-IN') : '—'}
          />
          <MetricTile
            label="Rows Skipped"
            value={stats ? stats.totalTransactionsSkipped.toLocaleString('en-IN') : '—'}
          />
          <MetricTile label="Last Import" value={fmtDate(stats?.lastImportedAt) ?? '—'} />
        </View>
      </SectionCard>

      <OptionPickerModal
        visible={timezonePickerOpen}
        title="Timezone"
        options={availableTimezones()}
        selected={timezone}
        onSelect={(tz) => {
          setTimezoneDraft(tz);
          setTimezonePickerOpen(false);
        }}
        onClose={() => setTimezonePickerOpen(false)}
      />

      {changePasswordOpen ? (
        <ChangePasswordSheet
          onClose={() => setChangePasswordOpen(false)}
          onSuccess={() => {
            // "Last changed" is read from the account, so it has to come back from the server
            // rather than being guessed at client-side.
            void queryClient.invalidateQueries({ queryKey: ['user-settings'] });
            void queryClient.invalidateQueries({ queryKey: ['devices'] });
          }}
        />
      ) : null}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  message: { fontSize: 14, textAlign: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  fieldLabel: { fontSize: 12, fontWeight: '500', marginBottom: 6 },
  picker: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    minHeight: 48,
  },
  pickerText: { fontSize: 15, flex: 1, marginRight: spacing.sm },
  chevron: { fontSize: 20, lineHeight: 20 },
  segments: { flexDirection: 'row', borderWidth: 1, borderRadius: radius.md, overflow: 'hidden' },
  segment: { flex: 1, minHeight: 44, alignItems: 'center', justifyContent: 'center' },
  segmentText: { fontSize: 13, fontWeight: '600' },
  hint: { fontSize: 11, lineHeight: 16, marginTop: spacing.sm },
  error: { fontSize: 13, marginTop: spacing.sm },
  saveRow: { alignItems: 'flex-end', marginVertical: spacing.sm, minHeight: 16 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  rowMeta: { fontSize: 12, marginTop: 2 },
  changePassword: { marginTop: spacing.sm, marginBottom: spacing.md },
  sessions: { marginTop: spacing.md },
  stepper: { flexDirection: 'row', alignItems: 'center', gap: spacing.md },
  stepButton: {
    width: 48,
    height: 48,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  stepButtonText: { fontSize: 22, fontWeight: '600', lineHeight: 26 },
  stepValue: { fontSize: 20, fontWeight: '700', minWidth: 64, textAlign: 'center' },
  disabled: { opacity: 0.4 },
  tiles: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
});
