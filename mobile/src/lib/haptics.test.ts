import * as Haptics from 'expo-haptics';
import { hapticImpact, hapticSelection, hapticSuccess, hapticWarning } from './haptics';

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

  it('fires a selection change', () => {
    hapticSelection();
    expect(haptics.selectionAsync).toHaveBeenCalledTimes(1);
  });

  it('fires a medium impact', () => {
    hapticImpact();
    expect(haptics.impactAsync).toHaveBeenCalledWith(Haptics.ImpactFeedbackStyle.Medium);
  });
});
