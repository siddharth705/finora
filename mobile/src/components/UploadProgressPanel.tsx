import { useEffect, useRef } from 'react';
import type { ReactNode } from 'react';
import Ionicons from '@expo/vector-icons/Ionicons';
import { AccessibilityInfo, Platform, StyleSheet, Text, View } from 'react-native';
import Animated, { useAnimatedStyle, useSharedValue, withTiming } from 'react-native-reanimated';
import { radius, spacing, useTheme } from '../theme';

export type UploadPanelState = 'idle' | 'uploading' | 'completed';

/**
 * The three-state upload widget shared by ImportScreen's file picker and its PDF-password panel
 * -- the mobile counterpart of the web app's Framer Motion version. Purely presentational, same as
 * the web version: the caller owns `state`/`progress` and the timing of when `state` becomes
 * 'completed' -- see ImportScreen's `celebrateThenAdvance`.
 *
 * The three states swap with no transition animation of their own (see the bug-fix comment below
 * for why) -- only the progress-fill bar animates, via Reanimated's `useAnimatedStyle`.
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
   *  uploading/completed states and the swap between whichever apply at that call site. */
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
      {/* Bug fix: these three branches used to be Animated.View with entering/exiting
          (FadeIn/FadeOut) -- fine individually, but a CSV's idle -> uploading -> completed
          sequence fires in well under a second, faster than those 150-200ms exit animations can
          finish. Fabric's mounting manager crashed outright when the next branch's insert landed
          before the previous one's animated removal had: "IllegalStateException: addViewAt:
          failed to insert view [598] into parent [578] at index 5" / "IndexOutOfBoundsException:
          index=5 count=4" -- reproduced in CI's Maestro import flow (PR #994), confirmed via the
          crash's device logcat, not guessed. Plain Views mount/unmount on React's own schedule
          with no separate native-thread animation to race. The progress-fill bar below keeps its
          `useAnimatedStyle`/`withTiming` width animation -- that's a style animation on values,
          not a mount/unmount layout transition, and isn't part of this crash class. */}
      {state === 'idle' && <View>{idle}</View>}

      {state === 'uploading' && (
        <View style={styles.progressWrap} testID="upload-progress">
          <Text style={[styles.progressText, { color: c.ink }]}>
            {progress < 100 ? `Uploading… ${progress}%` : 'Reading statement…'}
          </Text>
          <View style={[styles.progressTrack, { backgroundColor: c.border }]}>
            <Animated.View style={[styles.progressFill, { backgroundColor: c.primary }, fillStyle]} />
          </View>
        </View>
      )}

      {state === 'completed' && (
        <View
          style={[styles.completed, { backgroundColor: c.ink }]}
          testID="upload-completed"
          accessible
          accessibilityLiveRegion="polite"
        >
          <Ionicons name="checkmark-circle" size={26} color={c.success} />
          <Text style={[styles.completedText, { color: c.card }]}>Completed</Text>
        </View>
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
