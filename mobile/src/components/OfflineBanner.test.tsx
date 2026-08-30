import { act, render, renderHook, screen } from '@testing-library/react-native';
import { AccessibilityInfo, Platform, Text } from 'react-native';
import { onlineManager } from '@tanstack/react-query';
import { OfflineBoundary, useOnline } from './OfflineBanner';
import { ThemeProvider } from '../theme';
import App from '../../App';

describe('useOnline', () => {
  afterEach(() => onlineManager.setOnline(true));

  it('is exported and tracks onlineManager', () => {
    onlineManager.setOnline(true);
    const { result } = renderHook(() => useOnline());
    expect(result.current).toBe(true);

    act(() => onlineManager.setOnline(false));
    expect(result.current).toBe(false);
  });
});

// Replaced so the mount test below stays a test of App's own composition rather than of the whole
// navigation tree. Everything above it -- the providers, and the boundary itself -- stays real.
jest.mock('../navigation/RootNavigator', () => {
  const { Text: RNText } = require('react-native');
  return { RootNavigator: () => <RNText>navigator</RNText> };
});

/**
 * Two different things are proved here, and the second is the one that matters.
 *
 * The first is ordinary: the banner appears when connectivity drops and clears when it returns.
 * The second is that App actually MOUNTS this component. A boundary that behaves perfectly in a
 * test and is wired into nothing is indistinguishable, from a user's seat, from not existing --
 * and a component test alone cannot tell those apart. An audit of this repo asserted exactly that
 * failure (that OfflineBoundary was dead code) on the strength of a grep whose scope missed
 * App.tsx, which sits outside src/. The claim was wrong, but the gap it pointed at was real: there
 * was nothing that would have caught it had it been true.
 *
 * So the mount test renders the real App and asserts a user-visible consequence, rather than
 * asserting that App.tsx contains a particular import.
 */

function setOnline(value: boolean) {
  act(() => onlineManager.setOnline(value));
}

const OFFLINE_TEXT = /No connection/i;

afterEach(() => onlineManager.setOnline(true));

describe('OfflineBoundary', () => {
  function renderBoundary() {
    return render(
      <ThemeProvider>
        <OfflineBoundary>
          <Text>protected content</Text>
        </OfflineBoundary>
      </ThemeProvider>
    );
  }

  it('shows nothing extra while online', () => {
    setOnline(true);
    renderBoundary();

    expect(screen.getByText('protected content')).toBeTruthy();
    expect(screen.queryByText(OFFLINE_TEXT)).toBeNull();
  });

  it('explains the stale data when connectivity drops, without hiding the app', () => {
    setOnline(true);
    renderBoundary();

    setOnline(false);

    expect(screen.getByText(OFFLINE_TEXT)).toBeTruthy();
    // Being offline must not cost the user access to what was already loaded.
    expect(screen.getByText('protected content')).toBeTruthy();
  });

  it('clears itself when the connection returns', () => {
    setOnline(false);
    renderBoundary();
    expect(screen.getByText(OFFLINE_TEXT)).toBeTruthy();

    setOnline(true);

    expect(screen.queryByText(OFFLINE_TEXT)).toBeNull();
    expect(screen.getByText('protected content')).toBeTruthy();
  });

  it('announces itself to a screen reader as a live region', () => {
    // Someone who cannot see the strip still needs to know the figures stopped updating.
    setOnline(false);
    renderBoundary();

    expect(screen.getByRole('alert')).toBeTruthy();
  });

  // accessibilityLiveRegion (asserted above) only reaches Android -- React Native has no iOS
  // equivalent, so VoiceOver needs an explicit announcement on the ONLINE -> OFFLINE transition.
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

    it('announces when connectivity drops', () => {
      setOnline(true);
      renderBoundary();

      setOnline(false);

      expect(announceSpy).toHaveBeenCalledTimes(1);
      expect(announceSpy).toHaveBeenCalledWith('No connection — showing the last data loaded');
    });

    it('does not announce on mount, even if already offline', () => {
      // Mirrors Android's live region, which also only speaks a change, not the app opening
      // already offline -- this keeps the two platforms symmetric rather than iOS being chattier.
      setOnline(false);
      renderBoundary();

      expect(announceSpy).not.toHaveBeenCalled();
    });

    it('does not announce again on unrelated rerenders while still offline', () => {
      setOnline(true);
      const view = renderBoundary();

      setOnline(false);
      expect(announceSpy).toHaveBeenCalledTimes(1);

      // Any rerender that doesn't change `online` (e.g. a theme or layout update) must not
      // re-trigger the announcement -- it fires on the transition, not on every render.
      view.rerender(
        <ThemeProvider>
          <OfflineBoundary>
            <Text>protected content</Text>
          </OfflineBoundary>
        </ThemeProvider>
      );

      expect(announceSpy).toHaveBeenCalledTimes(1);
    });

    it('announces again on a second drop after recovering', () => {
      setOnline(true);
      renderBoundary();

      setOnline(false);
      setOnline(true);
      setOnline(false);

      const offlineCalls = announceSpy.mock.calls.filter(
        ([msg]) => msg === 'No connection — showing the last data loaded'
      );
      expect(offlineCalls).toHaveLength(2);
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

    it('still shows the visible banner and live region, but never calls the iOS announcement API', () => {
      setOnline(true);
      renderBoundary();

      setOnline(false);

      expect(screen.getByText(OFFLINE_TEXT)).toBeTruthy();
      expect(screen.getByRole('alert')).toBeTruthy();
      expect(announceSpy).not.toHaveBeenCalled();
    });
  });

  describe('back online feedback', () => {
    const BACK_ONLINE_TEXT = /Back online/i;

    beforeEach(() => jest.useFakeTimers());
    afterEach(() => {
      jest.useRealTimers();
      onlineManager.setOnline(true);
    });

    it('shows a transient success message when connectivity returns, then clears it, without hiding the app', () => {
      setOnline(false);
      renderBoundary();
      expect(screen.getByText(OFFLINE_TEXT)).toBeTruthy();

      setOnline(true);
      expect(screen.getByText(BACK_ONLINE_TEXT)).toBeTruthy();
      expect(screen.getByText('protected content')).toBeTruthy();

      act(() => { jest.advanceTimersByTime(2500); });
      expect(screen.queryByText(BACK_ONLINE_TEXT)).toBeNull();
      expect(screen.getByText('protected content')).toBeTruthy();
    });

    it('does not show the back-online message on initial mount while already online', () => {
      setOnline(true);
      renderBoundary();

      expect(screen.queryByText(BACK_ONLINE_TEXT)).toBeNull();
    });

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

      it('announces when connectivity returns', () => {
        setOnline(false);
        renderBoundary();
        announceSpy.mockClear();

        setOnline(true);

        expect(announceSpy).toHaveBeenCalledWith('Back online — refreshing your data');
      });
    });

    describe('on Android', () => {
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

      it('still shows the visible back-online banner, but never calls the iOS announcement API', () => {
        setOnline(false);
        renderBoundary();

        setOnline(true);

        expect(screen.getByText(BACK_ONLINE_TEXT)).toBeTruthy();
        expect(announceSpy).not.toHaveBeenCalled();
      });
    });
  });
});

describe('the app actually mounts it', () => {
  it('renders the offline strip over the real App tree when the connection is down', () => {
    setOnline(false);
    render(<App />);

    // If App ever stops wrapping the navigator in the boundary, this line fails. That is the
    // entire point: it asserts the user-visible consequence of the wiring, not the wiring.
    expect(screen.getByText(OFFLINE_TEXT)).toBeTruthy();
    expect(screen.getByText('navigator')).toBeTruthy();

    setOnline(true);

    expect(screen.queryByText(OFFLINE_TEXT)).toBeNull();
    expect(screen.getByText('navigator')).toBeTruthy();
  });
});
