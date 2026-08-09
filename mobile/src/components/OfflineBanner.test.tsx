import { act, render, screen } from '@testing-library/react-native';
import { Text } from 'react-native';
import { onlineManager } from '@tanstack/react-query';
import { OfflineBoundary } from './OfflineBanner';
import { ThemeProvider } from '../theme';
import App from '../../App';

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
