import { useEffect, useState } from 'react';
import { StyleSheet, Text } from 'react-native';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { useQueryClient } from '@tanstack/react-query';
import { AuthScreenLayout } from '../../components/AuthScreenLayout';
import { Button } from '../../components/Button';
import { emailChangeApi } from '../../api/endpoints';
import { toUserMessage } from '../../lib/apiError';
import { spacing, useTheme } from '../../theme';
import type { MoreStackParamList } from '../../navigation/types';

type Props = NativeStackScreenProps<MoreStackParamList, 'VerifyEmailChange'>;

/**
 * Phase 4, ported from frontend/src/pages/VerifyEmailChange.tsx. Reached from the link
 * EmailChangeService emails to the NEW address, via the deep link RootNavigator registers
 * (`finora://email-change-verify?sessionId=...&token=...`) -- see its own doc comment on why
 * that's a custom-scheme link with a "Open in the Finora app" affordance on the web confirmation
 * page, not a true universal/app link (this repo has no way to host or verify the
 * apple-app-site-association / assetlinks.json files that would need).
 *
 * Same verify()-falls-back-to-complete() chain as web, and for the identical reason: verify()
 * requires the session to be exactly STARTED server-side, so revisiting this screen (the app
 * backgrounding and resuming mid-flow, a double-tap on the link) after an earlier visit already
 * advanced it past STARTED would otherwise show a false failure even though the change genuinely
 * succeeded. complete() is idempotent and authoritative either way.
 */
export function VerifyEmailChangeScreen({ navigation, route }: Props) {
  const c = useTheme();
  const queryClient = useQueryClient();
  const { sessionId, token } = route.params ?? {};
  // Missing params is a fact about this screen's own props, known at mount -- deriving it into
  // the initial state (rather than setState-ing it from inside the effect below) is what keeps
  // that effect free of a synchronous setState call in its body, which react-hooks/set-state-in-
  // effect flags as a cascading-render risk.
  const missingParams = !sessionId || !token;
  const [error, setError] = useState<string | null>(
    missingParams ? 'This link is missing information — please use the link from the email exactly as sent.' : null,
  );
  const [loading, setLoading] = useState(!missingParams);
  const [newEmail, setNewEmail] = useState<string | null>(null);

  useEffect(() => {
    if (!sessionId || !token) return;

    async function run(id: string, t: string) {
      try {
        await emailChangeApi.verify(id, t);
      } catch (verifyErr) {
        try {
          const res = await emailChangeApi.complete(id);
          setNewEmail(res.email);
        } catch {
          setError(toUserMessage(verifyErr, 'This confirmation link is invalid or has expired.'));
        }
        return;
      }
      try {
        const res = await emailChangeApi.complete(id);
        setNewEmail(res.email);
      } catch (completeErr) {
        setError(toUserMessage(completeErr, 'This confirmation link is invalid or has expired.'));
      }
    }

    void run(sessionId, token).finally(() => {
      setLoading(false);
      // The email shown throughout the app (Settings' Security section, anywhere else user-settings
      // is read) comes from this same query -- it has to refetch for the new address to appear
      // without the user force-quitting the app.
      void queryClient.invalidateQueries({ queryKey: ['user-settings'] });
    });
  }, [sessionId, token, queryClient]);

  return (
    <AuthScreenLayout
      title={loading ? 'Confirming your new email…' : newEmail ? 'Email updated' : 'Confirmation failed'}
      error={!loading && !newEmail ? error : null}
    >
      {!loading && newEmail ? (
        <Text style={[styles.body, { color: c.muted }]}>
          Your account email is now {newEmail}. Sign in with this address from now on.
        </Text>
      ) : null}
      {!loading ? <Button label="Back to Settings" onPress={() => navigation.navigate('Settings')} /> : null}
    </AuthScreenLayout>
  );
}

const styles = StyleSheet.create({
  body: {
    fontSize: 13,
    lineHeight: 20,
    marginBottom: spacing.md,
  },
});
