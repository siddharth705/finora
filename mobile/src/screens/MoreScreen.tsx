import { Alert, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import type { NativeStackScreenProps } from '@react-navigation/native-stack';
import { Card } from '../components/Card';
import { useAuth } from '../context/AuthContext';
import { radius, spacing, useTheme } from '../theme';
import type { MoreStackParamList } from '../navigation/types';

type Props = NativeStackScreenProps<MoreStackParamList, 'MoreHome'>;

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

      <Card style={styles.profileCard}>
        <View style={[styles.avatar, { backgroundColor: c.primary }]}>
          <Text style={styles.avatarText}>{(fullName ?? email ?? '?').charAt(0).toUpperCase()}</Text>
        </View>
        <View style={styles.profileText}>
          <Text style={[styles.name, { color: c.ink }]} numberOfLines={1}>
            {fullName ?? 'Your account'}
          </Text>
          <Text style={[styles.email, { color: c.muted }]} numberOfLines={1}>
            {email}
          </Text>
        </View>
      </Card>

      <Card style={styles.menuCard}>
        <Pressable
          onPress={() => navigation.navigate('Accounts')}
          style={[styles.menuRow, { borderBottomColor: c.border }]}
          android_ripple={{ color: c.border }}
        >
          <Text style={[styles.menuLabel, { color: c.ink }]}>Accounts</Text>
          <Text style={[styles.chevron, { color: c.muted }]}>›</Text>
        </Pressable>

        {/* Budgets, Goals, Statement History, and Settings land in later phases -- listed as
            disabled rows rather than omitted so the shape of this menu doesn't shift under
            users as each one arrives, but they're visibly not tappable yet rather than
            pretending to work. */}
        {['Budgets', 'Goals', 'Statement History', 'Settings'].map((label) => (
          <View key={label} style={[styles.menuRow, { borderBottomColor: c.border }]}>
            <Text style={[styles.menuLabel, { color: c.muted }]}>{label}</Text>
            <Text style={[styles.soon, { color: c.muted, backgroundColor: c.primaryLight }]}>Soon</Text>
          </View>
        ))}
      </Card>

      <Pressable onPress={confirmSignOut} style={styles.signOutRow} hitSlop={8}>
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
  avatarText: { color: '#fff', fontWeight: '700', fontSize: 18 },
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
  soon: {
    fontSize: 10,
    fontWeight: '600',
    paddingHorizontal: 8,
    paddingVertical: 3,
    borderRadius: radius.md,
    overflow: 'hidden',
  },
  signOutRow: { marginTop: spacing.lg, alignItems: 'center' },
  signOut: { fontSize: 14, fontWeight: '600' },
});
