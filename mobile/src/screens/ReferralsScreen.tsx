import { ActivityIndicator, Linking, Platform, Pressable, ScrollView, Share, StyleSheet, Text, View } from 'react-native';
import { useQuery } from '@tanstack/react-query';
import * as Clipboard from 'expo-clipboard';
import Ionicons from '@expo/vector-icons/Ionicons';
import { Card } from '../components/Card';
import { MetricTile } from '../components/AccountUI';
import { referralsApi } from '../api/endpoints';
import { useTransientFlag } from '../lib/useTransientFlag';
import { radius, spacing, useTheme } from '../theme';

const STEPS: { icon: keyof typeof Ionicons.glyphMap; label: string; caption: string }[] = [
  { icon: 'share-social-outline', label: 'Share your code', caption: 'Send it to a friend' },
  { icon: 'person-add-outline', label: 'They sign up', caption: 'Using your code' },
  { icon: 'people-outline', label: 'You see it here', caption: 'In your count below' },
];

function shareMessage(code: string) {
  return `Join me on Fynora! Use my referral code ${code} when you sign up.`;
}

/**
 * Deep-links straight into WhatsApp/SMS/Mail rather than the generic OS share sheet -- these are
 * fixed brand/system colors, not theme tokens, the same way a WhatsApp or Gmail icon stays its own
 * color in every app that shows one, light or dark. `canOpenURL` gates WhatsApp specifically since
 * it's the one channel that isn't guaranteed to be installed; `sms:`/`mailto:` are handled by the
 * OS on both platforms, so those two skip the check.
 */
const CHANNELS: {
  key: string;
  label: string;
  icon: keyof typeof Ionicons.glyphMap;
  color: string;
  url: (code: string) => string;
  guarded?: boolean;
}[] = [
  { key: 'whatsapp', label: 'WhatsApp', icon: 'logo-whatsapp', color: '#25D366',
    url: (code) => `whatsapp://send?text=${encodeURIComponent(shareMessage(code))}`, guarded: true },
  { key: 'sms', label: 'Messages', icon: 'chatbubble-outline', color: '#0A84FF',
    url: (code) => Platform.OS === 'ios'
      ? `sms:&body=${encodeURIComponent(shareMessage(code))}`
      : `sms:?body=${encodeURIComponent(shareMessage(code))}` },
  { key: 'email', label: 'Email', icon: 'mail-outline', color: '#4C8BF5',
    url: (code) => `mailto:?subject=${encodeURIComponent('Join me on Fynora')}&body=${encodeURIComponent(shareMessage(code))}` },
];

/**
 * Refer & Earn MVP (mobile) -- ported from frontend/src/pages/Referrals.tsx, cut down to the same
 * scope: a shareable code, a copy/share action, and how many people have joined through it. No
 * wallet balance, no reward tiers or milestones, no per-referral status list -- there is no
 * backend data to honestly show for any of those (see ReferralService's own doc comment for the
 * scope this replaced). The 3-step strip below deliberately stops at "you see it here", not at a
 * reward, because nothing is credited yet.
 *
 * Mobile has no equivalent of the web's `?ref=` URL param, so there's nothing to build a share
 * LINK out of -- the code itself is the thing to copy/share and hand to a friend, who types it
 * into their own Register screen's "Referral code (optional)" field.
 */
export function ReferralsScreen() {
  const c = useTheme();
  const [copied, triggerCopied] = useTransientFlag();

  const { data, isLoading, isError, refetch } = useQuery({
    queryKey: ['referrals-mine'],
    queryFn: () => referralsApi.mine(),
  });

  async function handleCopy() {
    if (!data?.code) return;
    await Clipboard.setStringAsync(data.code);
    triggerCopied();
  }

  async function handleShare() {
    if (!data?.code) return;
    try {
      await Share.share({ message: shareMessage(data.code) });
    } catch {
      // User dismissed the share sheet, or the OS rejected it -- nothing more useful to do than
      // leave the code visible in the field above for a manual copy.
    }
  }

  async function handleChannel(channel: (typeof CHANNELS)[number]) {
    if (!data?.code) return;
    const url = channel.url(data.code);
    if (channel.guarded && !(await Linking.canOpenURL(url))) {
      // App not installed -- fall back to the OS share sheet rather than doing nothing.
      await handleShare();
      return;
    }
    await Linking.openURL(url);
  }

  if (isLoading) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <ActivityIndicator size="large" color={c.primary} />
      </View>
    );
  }

  if (isError || !data) {
    return (
      <View style={[styles.centered, { backgroundColor: c.bg }]}>
        <Text style={[styles.message, { color: c.muted }]}>Couldn&apos;t load your referral code.</Text>
        <Pressable onPress={() => void refetch()} accessibilityRole="button">
          <Text style={[styles.retry, { color: c.primary }]}>Try again</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <ScrollView style={{ backgroundColor: c.bg }} contentContainerStyle={styles.content}>
      {/* Header hidden (AppTabs.tsx) -- this large title is the screen's own, matching the
          design reference's hero weight rather than the small native-header title used
          elsewhere. */}
      <Text style={[styles.screenTitle, { color: c.ink }]}>Refer &amp; Earn</Text>
      <Text style={[styles.screenSubtitle, { color: c.muted }]}>
        Help your friends take control of their finances.
      </Text>

      {/* A lightweight stand-in for the design reference's illustration -- this app has no
          character artwork asset to embed, so two overlapping avatar glyphs plus a hand-drawn-
          style annotation carry the same "you and a friend" idea without literally drawing
          people. Copy stops short of promising a reward, unlike the reference's speech bubbles --
          nothing is credited in this MVP yet. */}
      <View style={styles.illustrationRow}>
        <View style={styles.avatarPair}>
          <View style={[styles.avatarGlyph, { backgroundColor: c.primary }]}>
            <Ionicons name="person" size={20} color={c.onPrimary} />
          </View>
          <View style={[styles.avatarGlyph, styles.avatarGlyphOverlap, { backgroundColor: c.primaryLight, borderColor: c.bg }]}>
            <Ionicons name="person" size={20} color={c.primary} />
          </View>
          <View style={[styles.heartBadge, { backgroundColor: c.card, borderColor: c.border }]}>
            <Ionicons name="heart" size={12} color={c.danger} />
          </View>
        </View>
        <Text style={[styles.annotation, { color: c.primary }]}>Better money habits, together</Text>
      </View>

      <Card>
        <View style={styles.stepsRow}>
          {STEPS.map((step, i) => (
            <View key={step.label} style={styles.stepGroup}>
              <View style={styles.step}>
                <View style={[styles.stepIcon, { backgroundColor: c.bg, borderColor: c.border }]}>
                  <Ionicons name={step.icon} size={16} color={c.primary} />
                </View>
                <Text style={[styles.stepLabel, { color: c.ink }]}>{step.label}</Text>
                <Text style={[styles.stepCaption, { color: c.muted }]}>{step.caption}</Text>
              </View>
              {i < STEPS.length - 1 ? (
                <Ionicons name="chevron-forward" size={14} color={c.border} style={styles.stepArrow} />
              ) : null}
            </View>
          ))}
        </View>
      </Card>

      <Card style={styles.codeCard}>
        <Text style={[styles.cardLabel, { color: c.ink }]}>Your referral code</Text>

        <View style={[styles.codeRow, { backgroundColor: c.bg, borderColor: c.border }]}>
          <Text style={[styles.code, { color: c.ink }]} selectable accessibilityLabel={`Referral code ${data.code}`}>
            {data.code}
          </Text>
          <Pressable
            onPress={() => void handleCopy()}
            style={[styles.iconButton, { backgroundColor: c.card, borderColor: c.border }]}
            accessibilityRole="button"
            accessibilityLabel={copied ? 'Copied' : 'Copy referral code'}
          >
            <Ionicons name={copied ? 'checkmark' : 'copy-outline'} size={16} color={copied ? c.success : c.ink} />
          </Pressable>
        </View>

        <Pressable
          onPress={() => void handleShare()}
          style={[styles.shareButton, { backgroundColor: c.primary }]}
          accessibilityRole="button"
          accessibilityLabel="Share referral code"
        >
          <Ionicons name="share-social-outline" size={16} color={c.onPrimary} />
          <Text style={[styles.shareButtonText, { color: c.onPrimary }]}>Share Invite</Text>
        </Pressable>

        <View style={styles.channelRow}>
          {CHANNELS.map((channel) => (
            <Pressable
              key={channel.key}
              onPress={() => void handleChannel(channel)}
              style={styles.channel}
              accessibilityRole="button"
              accessibilityLabel={`Share via ${channel.label}`}
            >
              <View style={[styles.channelIcon, { backgroundColor: channel.color }]}>
                <Ionicons name={channel.icon} size={20} color="#FFFFFF" />
              </View>
              <Text style={[styles.channelLabel, { color: c.muted }]}>{channel.label}</Text>
            </Pressable>
          ))}
          <Pressable
            onPress={() => void handleShare()}
            style={styles.channel}
            accessibilityRole="button"
            accessibilityLabel="More share options"
          >
            <View style={[styles.channelIcon, { backgroundColor: c.bg, borderWidth: 1, borderColor: c.border }]}>
              <Ionicons name="ellipsis-horizontal" size={20} color={c.ink} />
            </View>
            <Text style={[styles.channelLabel, { color: c.muted }]}>More</Text>
          </Pressable>
        </View>
      </Card>

      <MetricTile label="Friends Referred" value={String(data.referralCount)} />
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  centered: { flex: 1, alignItems: 'center', justifyContent: 'center', padding: spacing.lg, gap: spacing.sm },
  message: { fontSize: 14, textAlign: 'center' },
  retry: { fontSize: 13, fontWeight: '600' },
  content: { padding: spacing.md, paddingBottom: spacing.xl, gap: spacing.md },

  screenTitle: { fontSize: 28, fontWeight: '800', letterSpacing: -0.3 },
  screenSubtitle: { fontSize: 14, marginTop: -4, lineHeight: 19 },

  illustrationRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.md, paddingVertical: spacing.xs },
  avatarPair: { flexDirection: 'row', alignItems: 'center' },
  avatarGlyph: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  avatarGlyphOverlap: { marginLeft: -14, borderWidth: 2 },
  heartBadge: {
    width: 22, height: 22, borderRadius: 11, borderWidth: 1,
    alignItems: 'center', justifyContent: 'center', marginLeft: -8, marginTop: -18,
  },
  // A small rotation is the cheapest way to read as a hand-drawn margin note (same trick the
  // design reference uses) without a custom font -- this app has no script typeface loaded.
  annotation: { flex: 1, fontSize: 13, fontWeight: '600', fontStyle: 'italic', transform: [{ rotate: '-2deg' }] },

  stepsRow: { flexDirection: 'row', alignItems: 'flex-start', justifyContent: 'space-between' },
  stepGroup: { flexDirection: 'row', alignItems: 'flex-start', flex: 1 },
  step: { flex: 1, alignItems: 'center', gap: 4 },
  stepIcon: { width: 32, height: 32, borderRadius: 16, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  stepLabel: { fontSize: 11, fontWeight: '600', textAlign: 'center' },
  stepCaption: { fontSize: 9.5, textAlign: 'center' },
  stepArrow: { marginTop: 8 },

  codeCard: { gap: spacing.sm },
  cardLabel: { fontSize: 13, fontWeight: '600' },
  codeRow: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between',
    borderWidth: 1, borderRadius: radius.md, paddingLeft: spacing.md, paddingRight: spacing.xs, minHeight: 52,
  },
  code: { fontSize: 18, fontWeight: '700', fontFamily: 'monospace', letterSpacing: 1 },
  iconButton: {
    width: 40, height: 40, borderRadius: radius.md, borderWidth: 1,
    alignItems: 'center', justifyContent: 'center',
  },
  shareButton: {
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: 8,
    borderRadius: radius.md, minHeight: 48,
  },
  shareButtonText: { fontSize: 14, fontWeight: '600' },

  channelRow: { flexDirection: 'row', justifyContent: 'space-between', paddingTop: spacing.xs },
  channel: { alignItems: 'center', gap: 6 },
  channelIcon: { width: 44, height: 44, borderRadius: 22, alignItems: 'center', justifyContent: 'center' },
  channelLabel: { fontSize: 10.5 },
});
