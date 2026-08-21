import { useState } from 'react';
import {
  ActivityIndicator, Pressable, RefreshControl, ScrollView, StyleSheet, Text, View,
} from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { Button } from '../components/Button';
import { ReadOnlyField, SaveStatus, SectionCard, VerifiedBadge } from '../components/AccountUI';
import { TextField } from '../components/TextField';
import { userApi } from '../api/endpoints';
import { toUserMessage } from '../lib/apiError';
import { fmtMonthYear, fmtRelativeTime, initials } from '../lib/format';
import { maskPhone } from '../lib/maskPhone';
import { useSingleFlight } from '../lib/useSingleFlight';
import { useTransientFlag } from '../lib/useTransientFlag';
import { FULL_NAME_PATTERN } from '../lib/validation';
import { spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<MoreStackParamList, 'Profile'>;

/**
 * Port of frontend/src/pages/Profile.tsx -- "who you are", as opposed to SettingsScreen's "how
 * Finora behaves for you". Security here is deliberately a read-only summary with a link across to
 * Settings, not a second set of action buttons.
 *
 * Same scope discipline as the web page: no avatar upload, no plan/subscription, no fabricated
 * "last login" or "security score". None of those exist on the backend, so none of them appear.
 */
export function ProfileScreen({ navigation }: Props) {
  const c = useTheme();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();

  // A draft overlaying the account's name: null means "nothing typed yet, follow the server".
  // Seeding real state from the query in an effect instead would render twice and would clobber
  // an in-progress edit every time the query refetched.
  const [nameDraft, setNameDraft] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [justSaved, confirmSaved] = useTransientFlag();
  const [error, setError] = useState<string | null>(null);

  const { data: user, isLoading, isError, isFetching, refetch } = useQuery({
    queryKey: ['user-settings'],
    queryFn: () => userApi.get(),
  });

  const savedFullName = user?.fullName ?? '';
  const fullName = nameDraft ?? savedFullName;
  const trimmed = fullName.trim();
  const dirty = trimmed !== savedFullName;
  const nameValid = FULL_NAME_PATTERN.test(trimmed);

  async function save() {
    if (!dirty) return;
    if (!nameValid) {
      setError('Enter a name using letters, spaces, hyphens or apostrophes.');
      return;
    }
    setError(null);
    await singleFlight(async () => {
      setSaving(true);
      try {
        const updated = await userApi.update({ fullName: trimmed });
        // Writes straight into the cache rather than only invalidating: the More menu and the
        // Dashboard greeting both read this name, and a refetch round-trip would leave them
        // showing the old one for a beat after the field already updated.
        queryClient.setQueryData(['user-settings'], updated);
        // Draft dropped so the field follows the account again, and shows whatever normalization
        // the server applied rather than the raw text that was typed.
        setNameDraft(null);
        confirmSaved();
      } catch (e) {
        setError(toUserMessage(e, 'Could not save your name. Try again.'));
      } finally {
        setSaving(false);
      }
    });
  }

  if (isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  if (isError || !user) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.message, { color: c.muted }]}>
          Couldn&apos;t load your profile — please try again later.
        </Text>
      </View>
    );
  }

  const passwordChanged = fmtRelativeTime(user.passwordChangedAt);

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
      refreshControl={
        <RefreshControl refreshing={isFetching && !isLoading} onRefresh={() => void refetch()} tintColor={c.primary} />
      }
    >
      {/* Shows the SAVED name, never the in-progress edit, so it can't contradict the "Unsaved
          changes" indicator sitting a few lines below it. */}
      <View style={[styles.identity, { backgroundColor: c.card, borderColor: c.border }]}>
        <View
          style={[styles.avatar, { backgroundColor: c.primary }]}
          accessibilityElementsHidden
          importantForAccessibility="no-hide-descendants"
        >
          <Text style={[styles.avatarText, { color: c.onPrimary }]}>{initials(savedFullName)}</Text>
        </View>
        <View style={styles.identityText}>
          <Text style={[styles.identityName, { color: c.ink }]} numberOfLines={1}>
            {savedFullName || 'Your account'}
          </Text>
          <Text style={[styles.identityEmail, { color: c.muted }]} numberOfLines={1}>
            {user.email}
          </Text>
          <Text style={[styles.identityMeta, { color: c.muted }]}>
            {user.phoneVerified ? 'Phone verified · ' : ''}Member since {fmtMonthYear(user.createdAt)}
          </Text>
        </View>
      </View>

      <SectionCard title="Personal Information" subtitle="Your name, email and phone on file">
        <TextField
          label="Full name"
          value={fullName}
          onChangeText={setNameDraft}
          autoCapitalize="words"
          error={dirty && trimmed.length > 0 && !nameValid ? 'Letters, spaces, hyphens and apostrophes only.' : null}
        />
        {/* Email and phone are read-only on purpose: changing either is an identity change the
            backend has no endpoint for, and a disabled-looking input the user can tap is worse
            than a plain fact. */}
        <ReadOnlyField label="Email" value={user.email} />
        <ReadOnlyField
          label="Phone number"
          value={user.phoneNumber ? maskPhone(user.phoneNumber) : '—'}
          trailing={user.phoneVerified ? <VerifiedBadge /> : null}
        />
        <ReadOnlyField label="Member since" value={fmtMonthYear(user.createdAt)} />

        {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}

        <View style={styles.saveRow}>
          <SaveStatus dirty={dirty} saving={saving} justSaved={justSaved} error={false} />
        </View>
        <Button
          label={saving ? 'Saving…' : 'Save changes'}
          onPress={() => void save()}
          loading={saving}
          disabled={!dirty || trimmed.length === 0}
        />
      </SectionCard>

      <SectionCard title="Security Overview" subtitle="A quick summary — manage these in Settings">
        <View style={[styles.row, { borderBottomColor: c.border }]}>
          <View style={styles.rowMain}>
            <Text style={[styles.rowTitle, { color: c.ink }]}>Password</Text>
            <Text style={[styles.rowMeta, { color: c.muted }]}>
              {passwordChanged ? `Last changed ${passwordChanged}` : 'Never changed'}
            </Text>
          </View>
        </View>
        <View style={styles.row}>
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

        <Pressable
          onPress={() => navigation.navigate('Settings')}
          style={[styles.manageLink, { backgroundColor: c.primaryLight }]}
          accessibilityRole="button"
          accessibilityLabel="Manage security in Settings"
        >
          <Text style={[styles.manageLinkText, { color: c.primary }]}>Manage Security →</Text>
        </Pressable>
      </SectionCard>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg },
  message: { fontSize: 14, textAlign: 'center' },
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  identity: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: 12,
    padding: spacing.md,
    marginBottom: spacing.md,
  },
  avatar: {
    width: 52,
    height: 52,
    borderRadius: 26,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: spacing.sm,
  },
  avatarText: { fontWeight: '700', fontSize: 18 },
  identityText: { flex: 1 },
  identityName: { fontSize: 17, fontWeight: '700' },
  identityEmail: { fontSize: 13, marginTop: 2 },
  identityMeta: { fontSize: 11, marginTop: 4 },
  error: { fontSize: 13, marginTop: spacing.sm },
  saveRow: { alignItems: 'flex-end', marginBottom: spacing.sm, minHeight: 16 },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 10,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: 'transparent',
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  rowTitle: { fontSize: 14, fontWeight: '600' },
  rowMeta: { fontSize: 12, marginTop: 2 },
  manageLink: {
    alignSelf: 'flex-start',
    borderRadius: 8,
    paddingHorizontal: 12,
    minHeight: 44,
    justifyContent: 'center',
    marginTop: spacing.sm,
  },
  manageLinkText: { fontSize: 13, fontWeight: '600' },
});
