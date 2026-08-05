import { useState } from 'react';
import { ActivityIndicator, Alert, Pressable, StyleSheet, Text, View } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { devicesApi, type DeviceSession } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { fmtRelativeTime } from '../../lib/format';
import { useSingleFlight } from '../../lib/useSingleFlight';
import { radius, spacing, useTheme } from '../../theme';

/**
 * Active Sessions -- every device currently holding a refresh token for this account.
 *
 * `DeviceController` has existed server-side with no caller on either client; the mobile roadmap
 * recommends shipping the UI here first, because "what am I signed in on" is a question people
 * actually ask on a phone. There is no web equivalent yet, so this is not a port.
 *
 * Note the backend sends no "this is the current device" flag, so no row can be highlighted as
 * yours -- and revoking your own session is therefore possible. That is why this confirms first
 * and says plainly what will happen.
 */
function deviceLabel(session: DeviceSession): string {
  // Both fields are best-effort labels parsed from a User-Agent, not a guaranteed fingerprint, so
  // every combination of present/absent has to read sensibly.
  if (session.browser && session.device) return `${session.browser} on ${session.device}`;
  return session.browser || session.device || 'Unknown device';
}

export function DeviceSessionsSection() {
  const c = useTheme();
  const queryClient = useQueryClient();
  const singleFlight = useSingleFlight();
  const [revokingId, setRevokingId] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const { data: sessions = [], isLoading, isError } = useQuery({
    queryKey: ['devices'],
    queryFn: () => devicesApi.list(),
  });

  function confirmRevoke(session: DeviceSession) {
    Alert.alert(
      'Sign out this device?',
      `${deviceLabel(session)} will be signed out the next time it refreshes. If this is the device you're using now, you'll be signed out too.`,
      [
        { text: 'Cancel', style: 'cancel' },
        { text: 'Sign out', style: 'destructive', onPress: () => void revoke(session) },
      ]
    );
  }

  async function revoke(session: DeviceSession) {
    setError(null);
    await singleFlight(async () => {
      setRevokingId(session.id);
      try {
        await devicesApi.revoke(session.id);
        void queryClient.invalidateQueries({ queryKey: ['devices'] });
      } catch (e) {
        setError(toUserMessage(e, 'Could not sign out that device.'));
      } finally {
        setRevokingId(null);
      }
    });
  }

  return (
    <View>
      <Text style={[styles.title, { color: c.ink }]}>Active Sessions</Text>
      <Text style={[styles.blurb, { color: c.muted }]}>
        Every device currently signed in to your account. Signing one out here ends that session
        the next time it needs to refresh.
      </Text>

      {isLoading ? (
        <ActivityIndicator color={c.primary} style={styles.loader} />
      ) : isError ? (
        <Text style={[styles.error, { color: c.danger }]}>
          Couldn&apos;t load your active sessions — please try again later.
        </Text>
      ) : sessions.length === 0 ? (
        <Text style={[styles.empty, { color: c.muted }]}>No active sessions found.</Text>
      ) : (
        sessions.map((s) => {
          const lastActive = fmtRelativeTime(s.lastSeenAt);
          return (
            <View key={s.id} style={[styles.row, { borderColor: c.border }]}>
              <View style={styles.rowMain}>
                <Text style={[styles.device, { color: c.ink }]} numberOfLines={1}>
                  {deviceLabel(s)}
                </Text>
                <Text style={[styles.meta, { color: c.muted }]} numberOfLines={1}>
                  {lastActive ? `Last active ${lastActive}` : 'Not used yet'}
                  {s.lastSeenIp ? ` · ${s.lastSeenIp}` : ''}
                </Text>
              </View>
              {revokingId === s.id ? (
                <ActivityIndicator size="small" color={c.muted} />
              ) : (
                <Pressable
                  onPress={() => confirmRevoke(s)}
                  hitSlop={8}
                  style={styles.revoke}
                  accessibilityRole="button"
                  accessibilityLabel={`Sign out ${deviceLabel(s)}`}
                >
                  <Text style={[styles.revokeText, { color: c.danger }]}>Sign out</Text>
                </Pressable>
              )}
            </View>
          );
        })
      )}

      {error ? <Text style={[styles.error, { color: c.danger }]}>{error}</Text> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  title: { fontSize: 14, fontWeight: '600' },
  blurb: { fontSize: 11, lineHeight: 16, marginTop: 2, marginBottom: spacing.sm },
  loader: { paddingVertical: spacing.sm },
  error: { fontSize: 12, paddingVertical: spacing.xs },
  empty: { fontSize: 12, fontStyle: 'italic', paddingVertical: spacing.xs },
  row: {
    flexDirection: 'row',
    alignItems: 'center',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    paddingVertical: 10,
    marginBottom: spacing.xs,
  },
  rowMain: { flex: 1, marginRight: spacing.sm },
  device: { fontSize: 13, fontWeight: '500' },
  meta: { fontSize: 11, marginTop: 2 },
  revoke: { minHeight: 44, justifyContent: 'center' },
  revokeText: { fontSize: 12, fontWeight: '600' },
});
