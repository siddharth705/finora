import { Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { Card } from '../components/Card';
import { useAuth } from '../context/AuthContext';
import { initials } from '../lib/format';
import { spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<MoreStackParamList, 'MoreHome'>;

/**
 * Ordered roughly by how often they're opened: the money you hold, then the plans against it, then
 * the reporting surfaces. Typed against the stack's own param list, so deleting or renaming a route
 * breaks this at compile time rather than at the tap.
 */
// 'SupportTicketDetail' excluded alongside 'MoreHome': its params ({ ticketId }) are required, so
// it has no zero-argument navigate() overload -- the same reason it isn't (and can't be) in the
// MENU_ITEMS list below, which calls navigate(route) with nothing else. Support has its own entry
// point in Settings instead (see SettingsScreen's "Help & Support" section), not this generic menu.
const MENU_ITEMS: { label: string; route: keyof Omit<MoreStackParamList, 'MoreHome' | 'SupportTicketDetail'> }[] = [
  { label: 'Accounts', route: 'Accounts' },
  { label: 'Investments', route: 'Investments' },
  { label: 'Budgets', route: 'Budgets' },
  { label: 'Goals', route: 'Goals' },
  { label: 'Reports', route: 'Reports' },
  { label: 'Insights', route: 'Insights' },
  { label: 'Review Categories', route: 'CategoryReview' },
  { label: 'Statement History', route: 'Statements' },
  { label: 'Subscription', route: 'Subscription' },
  { label: 'Refer & Earn', route: 'Referrals' },
  { label: 'Settings', route: 'Settings' },
];

export function MoreScreen({ navigation }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();
  const { email, fullName, logout } = useAuth();

  function confirmSignOut() {
    Alert.alert('Sign out?', 'You’ll need to sign in again to access your account.', [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Sign out', style: 'destructive', onPress: logout },
    ]);
  }

  return (
    <ScrollView
      style={{ backgroundColor: c.bg }}
      contentContainerStyle={[styles.content, { paddingTop: insets.top + spacing.md }]}
    >
      <Text style={[styles.title, { color: c.ink }]}>More</Text>

      {/* The whole card opens Profile -- tapping your own name and photo to edit them is the
          convention on every phone, and it saves a menu row for the same destination. */}
      <Pressable
        onPress={() => navigation.navigate('Profile')}
        accessibilityRole="button"
        accessibilityLabel={`Profile: ${fullName ?? email ?? 'your account'}`}
        accessibilityHint="Opens your profile"
      >
        <Card style={styles.profileCard}>
          {/* Decorative initial -- the name and email are read out right beside it, so announcing
              a lone "S" first is pure noise. */}
          <View
            style={[styles.avatar, { backgroundColor: c.primary }]}
            accessibilityElementsHidden
            importantForAccessibility="no-hide-descendants"
          >
            <Text style={[styles.avatarText, { color: c.onPrimary }]}>{initials(fullName ?? email)}</Text>
          </View>
          <View style={styles.profileText}>
            <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>
              {fullName ?? 'Your account'}
            </Text>
            <Text style={[styles.email, { color: c.muted }]} numberOfLines={1}>
              {email}
            </Text>
          </View>
          <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
        </Card>
      </Pressable>

      <Card style={styles.menuCard}>
        {MENU_ITEMS.map(({ label, route }) => (
          <Pressable
            key={route}
            onPress={() => navigation.navigate(route)}
            style={[styles.menuRow, { borderBottomColor: c.border }]}
            android_ripple={{ color: c.border }}
            accessibilityRole="button"
            accessibilityLabel={label}
          >
            <Text style={[styles.menuLabel, { color: c.ink }]}>{label}</Text>
            {/* Decorative -- the row already announces itself as a button, so a screen reader
                reading "greater-than sign" here would be noise. */}
            <Text style={[styles.chevron, { color: c.muted }]} accessibilityElementsHidden importantForAccessibility="no">›</Text>
          </Pressable>
        ))}

      </Card>

      <Pressable onPress={confirmSignOut} style={styles.signOutRow} hitSlop={12} accessibilityRole="button">
        <Text style={[styles.signOut, { color: c.danger }]}>Sign out</Text>
      </Pressable>
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  content: { padding: spacing.md, paddingBottom: spacing.xl },
  title: { fontSize: 22, fontWeight: '700', marginBottom: spacing.md },
  profileCard: { flexDirection: 'row', alignItems: 'center' },
  avatar: {
    width: 44,
    height: 44,
    borderRadius: 22,
    alignItems: 'center',
    justifyContent: 'center',
    marginRight: spacing.sm,
  },
  avatarText: { fontWeight: '700', fontSize: 18 },
  profileText: { flex: 1 },
  name: { fontSize: 15, fontWeight: '600' },
  email: { fontSize: 12, marginTop: 2 },
  menuCard: { marginTop: spacing.md, paddingVertical: 0 },
  menuRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: 14,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  menuLabel: { fontSize: 14 },
  chevron: { fontSize: 20, lineHeight: 20 },
  signOutRow: { marginTop: spacing.lg, alignItems: 'center' },
  signOut: { fontSize: 14, fontWeight: '600' },
});
