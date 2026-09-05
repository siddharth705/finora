import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import Ionicons from '@expo/vector-icons/Ionicons';
import { AccessibilityInfo, Platform, StyleSheet, Text, View } from 'react-native';
import Animated, {
  FadeIn, FadeOut, useAnimatedStyle, useSharedValue, withTiming,
} from 'react-native-reanimated';
import { radius, spacing, useTheme } from '../theme';

export type UploadPanelState = 'idle' | 'uploading' | 'completed';

/**
 * The three-state upload micro-interaction shared by ImportScreen's file picker and its
 * PDF-password panel -- the same widget the web app builds with Framer Motion's `layout`
 * animation, done here with Reanimated (there is no Framer Motion equivalent in React Native).
 * Purely presentational, same as the web version: the caller owns `state`/`progress` and the
 * timing of when `state` becomes 'completed' -- see ImportScreen's `celebrateThenAdvance`.
 */
export function UploadProgressPanel({
  state,
  progress,
  idle,
}: {
  state: UploadPanelState;
  /** 0-100. Only read while `state === 'uploading'`; 100 reads as "Reading statement…" rather
   *  than "Uploading… 100%" -- the network transfer finishing is not the server finishing, see
   *  ProgressCallback's own doc comment in api/endpoints.ts. */
  progress: number;
  /** The caller's own idle-state content (a "Choose a file" button, or an "Upload statement"
   *  button plus its "Choose a different file" link) -- this component only supplies the
   *  uploading/completed states and the animated swap between whichever apply at that call site. */
  idle: ReactNode;
}) {
  const c = useTheme();
  const fillPct = useSharedValue(progress);

  // Not mutated directly in the render body -- SharedValue writes are a side effect, and this
  // keeps the same "animate on prop change" shape useAnimatedStyle-driven components elsewhere in
  // this app already use (see Button.tsx's press-scale).
  useEffect(() => {
    fillPct.value = withTiming(progress, { duration: 200 });
  }, [progress, fillPct]);

  const fillStyle = useAnimatedStyle(() => ({ width: `${fillPct.value}%` }));

  // accessibilityLiveRegion below is Android-only -- React Native has no iOS equivalent, so a
  // VoiceOver user gets no signal that the upload actually succeeded before the screen silently
  // moves on to review a moment later (same gap, same fix, as RootWarningBanner/OfflineBanner).
  // Announced only on the transition INTO 'completed', not on every render while it's showing.
  const wasCompleted = useRef(state === 'completed');
  useEffect(() => {
    if (Platform.OS === 'ios' && !wasCompleted.current && state === 'completed') {
      AccessibilityInfo.announceForAccessibility('Completed');
    }
    wasCompleted.current = state === 'completed';
  }, [state]);

  return (
    <View>
      {state === 'idle' && (
        <Animated.View entering={FadeIn.duration(200)} exiting={FadeOut.duration(150)}>
          {idle}
        </Animated.View>
      )}

      {state === 'uploading' && (
        <Animated.View
          entering={FadeIn.duration(200)}
          exiting={FadeOut.duration(150)}
          style={styles.progressWrap}
          testID="upload-progress"
        >
          <Text style={[styles.progressText, { color: c.ink }]}>
            {progress < 100 ? `Uploading… ${progress}%` : 'Reading statement…'}
          </Text>
          <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
            <Animated.View style={[styles.progressFill, { backgroundColor: c.primary }, fillStyle]} />
          </View>
        </Animated.View>
      )}

      {state === 'completed' && (
        <Animated.View
          entering={FadeIn.duration(200)}
          exiting={FadeOut.duration(150)}
          style={[styles.completed, { backgroundColor: c.ink }]}
          testID="upload-completed"
          accessible
          accessibilityLiveRegion="polite"
        >
          <Ionicons name="checkmark-circle" size={26} color={c.success} />
          <Text style={[styles.completedText, { color: c.card }]}>Completed</Text>
        </Animated.View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  progressWrap: { gap: spacing.sm },
  progressText: { fontSize: 13, fontWeight: '600' },
  progressTrack: { height: 8, borderRadius: radius.md, overflow: 'hidden' },
  progressFill: { height: 8, borderRadius: radius.md },
  completed: {
    borderRadius: radius.xl,
    paddingVertical: spacing.lg,
    alignItems: 'center',
    gap: spacing.sm,
  },
  completedText: { fontSize: 14, fontWeight: '600' },
});
