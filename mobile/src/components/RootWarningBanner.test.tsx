import { render, screen, waitFor } from '@testing-library/react-native';
import { Text } from 'react-native';
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
