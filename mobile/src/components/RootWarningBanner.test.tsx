import { render, screen, waitFor } from '@testing-library/react-native';
import { AccessibilityInfo, Platform, Text } from 'react-native';
import * as Device from 'expo-device';
import { RootWarningBoundary } from './RootWarningBanner';
import { ThemeProvider } from '../theme';
import App from '../../App';

// Same reasoning as OfflineBanner.test.tsx's identical mock: keeps the mount test a test of App's
// own composition, not of the whole navigation tree.
jest.mock('../navigation/RootNavigator', () => {
  const { Text: RNText } = require('react-native');
  return { RootNavigator: () => <RNText>navigator</RNText> };
});

const mockedIsRooted = Device.isRootedExperimentalAsync as jest.MockedFunction<
  typeof Device.isRootedExperimentalAsync
>;

const ROOT_TEXT = /appears to be rooted or jailbroken/i;

describe('RootWarningBoundary', () => {
  function renderBoundary() {
    return render(
      <ThemeProvider>
        <RootWarningBoundary>
          <Text>protected content</Text>
        </RootWarningBoundary>
      </ThemeProvider>
    );
  }

  it('shows nothing extra on an unflagged device', async () => {
    mockedIsRooted.mockResolvedValueOnce(false);
    renderBoundary();

    expect(await screen.findByText('protected content')).toBeTruthy();
    expect(screen.queryByText(ROOT_TEXT)).toBeNull();
  });

  it('warns without hiding the app when the device is flagged', async () => {
    mockedIsRooted.mockResolvedValueOnce(true);
    renderBoundary();

    await waitFor(() => expect(screen.getByText(ROOT_TEXT)).toBeTruthy());
    // The whole point of a warning, not a block -- access to the app itself is unaffected.
    expect(screen.getByText('protected content')).toBeTruthy();
  });

  it('fails closed to unflagged when the check itself errors', async () => {
    mockedIsRooted.mockRejectedValueOnce(new Error('native module unavailable'));
    renderBoundary();

    expect(await screen.findByText('protected content')).toBeTruthy();
    expect(screen.queryByText(ROOT_TEXT)).toBeNull();
  });

  it('announces itself to a screen reader as a live region', async () => {
    mockedIsRooted.mockResolvedValueOnce(true);
    renderBoundary();

    await waitFor(() => expect(screen.getByRole('alert')).toBeTruthy());
  });

  // accessibilityLiveRegion (asserted above) only reaches Android -- React Native has no iOS
  // equivalent, so VoiceOver needs an explicit announcement on the false -> true transition, same
  // gap and same fix as OfflineBanner's identical pattern.
  describe('iOS VoiceOver announcement', () => {
    const originalOS = Platform.OS;
    let announceSpy: jest.SpyInstance;

    beforeEach(() => {
      Platform.OS = 'ios';
      announceSpy = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
    });

    afterEach(() => {
      Platform.OS = originalOS;
      announceSpy.mockRestore();
    });

    it('announces once the device is confirmed rooted', async () => {
      mockedIsRooted.mockResolvedValueOnce(true);
      renderBoundary();

      await waitFor(() => expect(announceSpy).toHaveBeenCalledTimes(1));
      expect(announceSpy).toHaveBeenCalledWith(
        "This device appears to be rooted or jailbroken — Fynora's own protections may not be fully effective here"
      );
    });

    it('does not announce on an unflagged device', async () => {
      mockedIsRooted.mockResolvedValueOnce(false);
      renderBoundary();

      await screen.findByText('protected content');
      expect(announceSpy).not.toHaveBeenCalled();
    });

    it('does not announce when the check itself errors (fails closed, same as the banner)', async () => {
      mockedIsRooted.mockRejectedValueOnce(new Error('native module unavailable'));
      renderBoundary();

      await screen.findByText('protected content');
      expect(announceSpy).not.toHaveBeenCalled();
    });

    it('does not announce again on an unrelated rerender after the initial flag', async () => {
      mockedIsRooted.mockResolvedValueOnce(true);
      const view = renderBoundary();

      await waitFor(() => expect(announceSpy).toHaveBeenCalledTimes(1));

      // The rooted check runs once on mount (empty dependency array) -- a later rerender must not
      // re-run the transition logic and must not re-announce.
      view.rerender(
        <ThemeProvider>
          <RootWarningBoundary>
            <Text>protected content</Text>
          </RootWarningBoundary>
        </ThemeProvider>
      );

      expect(announceSpy).toHaveBeenCalledTimes(1);
    });
  });

  describe('on Android, unaffected by the iOS announcement path', () => {
    const originalOS = Platform.OS;
    let announceSpy: jest.SpyInstance;

    beforeEach(() => {
      Platform.OS = 'android';
      announceSpy = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
    });

    afterEach(() => {
      Platform.OS = originalOS;
      announceSpy.mockRestore();
    });

    it('still shows the visible banner and live region, but never calls the iOS announcement API', async () => {
      mockedIsRooted.mockResolvedValueOnce(true);
      renderBoundary();

      await waitFor(() => expect(screen.getByText(ROOT_TEXT)).toBeTruthy());
      expect(screen.getByRole('alert')).toBeTruthy();
      expect(announceSpy).not.toHaveBeenCalled();
    });
  });
});

describe('the app actually mounts it', () => {
  it('renders the root warning over the real App tree when the device is flagged', async () => {
    mockedIsRooted.mockResolvedValueOnce(true);
    render(<App />);

    // If App ever stops wrapping the navigator in the boundary, this line fails -- the same
    // wiring assertion OfflineBanner.test.tsx makes, and for the same reason.
    await waitFor(() => expect(screen.getByText(ROOT_TEXT)).toBeTruthy());
    expect(screen.getByText('navigator')).toBeTruthy();
  });
});
