import type { ReactNode } from 'react';
import {
  KeyboardAvoidingView,
  Platform,
  ScrollView,
  StyleSheet,
  Text,
  View,
} from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { BrandMark } from './BrandMark';
import { fonts, radius, spacing, useTheme } from '../theme';

/**
 * Shared chrome for the four auth screens: brand mark, title/subtitle, an inline error banner,
 * and the card the form sits in.
 *
 * The web versions pair each auth card with a large marketing panel (feature list, headline,
 * decorative tiles) that's `hidden lg:block` -- i.e. deliberately not rendered at phone widths.
 * Porting it here would mean putting content on a 390pt screen that the web app specifically
 * chose not to show at that size, so it's dropped rather than reproduced.
 */
interface Props {
  title: string;
  subtitle?: string;
  error?: string | null;
  banner?: string | null;
  children: ReactNode;
  footer?: ReactNode;
}

export function AuthScreenLayout({ title, subtitle, error, banner, children, footer }: Props) {
  const c = useTheme();
  const insets = useSafeAreaInsets();

  return (
    <KeyboardAvoidingView
      style={[styles.flex, { backgroundColor: c.bg }]}
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
    >
      <ScrollView
        contentContainerStyle={[
          styles.scroll,
          { paddingTop: insets.top + spacing.lg, paddingBottom: insets.bottom + spacing.lg },
        ]}
        keyboardShouldPersistTaps="handled"
      >
        <View style={styles.brandRow}>
          <BrandMark size={30} />
          <Text style={[styles.brandName, { color: c.ink }]}>FYNORA</Text>
        </View>

        <View style={[styles.card, { backgroundColor: c.card, borderColor: c.border }]}>
          <Text style={[styles.title, { color: c.ink }]}>{title}</Text>
          {subtitle ? <Text style={[styles.subtitle, { color: c.muted }]}>{subtitle}</Text> : null}

          {banner ? (
            <Text style={[styles.banner, { color: c.success, backgroundColor: c.successBg }]}>{banner}</Text>
          ) : null}
          {error ? (
            <Text style={[styles.banner, { color: c.danger, backgroundColor: c.dangerBg }]}>{error}</Text>
          ) : null}

          {children}
        </View>

        {footer ? <View style={styles.footer}>{footer}</View> : null}
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  flex: { flex: 1 },
  scroll: {
    flexGrow: 1,
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
  },
  brandRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginBottom: spacing.lg,
    alignSelf: 'center',
  },
  brandName: {
    fontFamily: fonts.display,
    fontSize: 17,
    letterSpacing: 1.2,
  },
  card: {
    borderWidth: 1,
    borderRadius: radius.xl,
    padding: spacing.lg,
  },
  title: {
    fontFamily: fonts.display,
    fontSize: 22,
    marginBottom: 4,
  },
  subtitle: {
    fontSize: 13,
    marginBottom: spacing.md,
  },
  banner: {
    fontSize: 13,
    borderRadius: radius.md,
    paddingHorizontal: 12,
    paddingVertical: 10,
    marginBottom: spacing.md,
    overflow: 'hidden',
  },
  footer: {
    marginTop: spacing.md,
    alignItems: 'center',
  },
});
