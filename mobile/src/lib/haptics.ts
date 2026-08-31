import * as Haptics from 'expo-haptics';

/**
 * Every haptic touchpoint in the app funnels through here rather than calling `expo-haptics`
 * directly: one place to change the mapping from "kind of feedback" to Expo's three underlying
 * APIs (impact/notification/selection), and one place a test can assert against instead of every
 * screen that fires one. See src/test/setup.ts for the module-level jest.mock.
 *
 * expo-haptics' promises resolve once the native call is dispatched, not once it's felt, and
 * nothing here awaits them -- a haptic is a fire-and-forget side effect of a UI event, not
 * something a caller should block on or fail over if it rejects. `fire` swallows the rejection
 * itself (a device/OS where the native call actually fails -- vibration hardware unavailable, a
 * permission quirk on some Android OEM build) rather than leaving it unhandled: an ignored
 * rejection would otherwise surface as an unhandled-promise-rejection warning (and, once Sentry is
 * wired up in production, get reported as crash-adjacent noise) for a failure no caller here was
 * ever going to react to regardless.
 */
function fire(promise: Promise<unknown>): void {
  promise.catch(() => {});
}

/** A flow completed: an import finished, a budget saved. */
export function hapticSuccess(): void {
  fire(Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success));
}

/**
 * A validation error stopped a submit. Deliberately `Warning`, not `Error` -- nothing failed on
 * the server, the form just isn't complete yet, and iOS's own semantics keep those separate.
 */
export function hapticWarning(): void {
  fire(Haptics.notificationAsync(Haptics.NotificationFeedbackType.Warning));
}

/**
 * A submit reached the server and failed there -- a network error, a rejected mutation. The
 * counterpart hapticWarning() is deliberately reserved for "the form isn't complete yet"; this is
 * for "the form was fine and it still didn't work," which is a different outcome the user should
 * be able to tell apart by feel, the same way iOS's own three-way Success/Warning/Error semantics
 * keep them apart.
 */
export function hapticError(): void {
  fire(Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error));
}

/** Picking one option among many -- a category, a filter -- where the feedback confirms the tap
 * registered rather than announcing an outcome. */
export function hapticSelection(): void {
  fire(Haptics.selectionAsync());
}

/** A physical acknowledgement for a gesture that isn't a simple tap -- e.g. a long-press that is
 * about to open a destructive confirmation. Medium matches expo-haptics' own default style. */
export function hapticImpact(): void {
  fire(Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Medium));
}
