import * as Haptics from 'expo-haptics';
import { hapticError, hapticImpact, hapticSelection, hapticSuccess, hapticWarning } from './haptics';

const haptics = Haptics as jest.Mocked<typeof Haptics>;

describe('haptics', () => {
  it('fires a success notification', () => {
    hapticSuccess();
    expect(haptics.notificationAsync).toHaveBeenCalledWith(Haptics.NotificationFeedbackType.Success);
  });

  it('fires a warning notification', () => {
    hapticWarning();
    expect(haptics.notificationAsync).toHaveBeenCalledWith(Haptics.NotificationFeedbackType.Warning);
  });

  it('fires an error notification, distinct from a warning', () => {
    hapticError();
    expect(haptics.notificationAsync).toHaveBeenCalledWith(Haptics.NotificationFeedbackType.Error);
  });

  it('fires a selection change', () => {
    hapticSelection();
    expect(haptics.selectionAsync).toHaveBeenCalledTimes(1);
  });

  it('fires a medium impact', () => {
    hapticImpact();
    expect(haptics.impactAsync).toHaveBeenCalledWith(Haptics.ImpactFeedbackStyle.Medium);
  });

  // A device/OS where the native call actually rejects (vibration hardware unavailable, a
  // permission quirk on some Android OEM build) must not leave the rejection unhandled -- these
  // are fire-and-forget by design, and an unhandled rejection would otherwise surface as noise
  // (a dev-mode warning, or crash-adjacent reporting once Sentry is wired up) for a failure no
  // caller was ever going to react to.
  it('does not produce an unhandled rejection when the native call rejects', async () => {
    haptics.notificationAsync.mockRejectedValueOnce(new Error('native call failed'));
    expect(() => hapticSuccess()).not.toThrow();
    // Let the rejected promise's own microtask (and this function's .catch) settle before the
    // test ends -- otherwise a rejection surfacing after the test completes wouldn't be caught by
    // anything, defeating the point of the assertion above.
    await Promise.resolve().then().then();
  });
});
