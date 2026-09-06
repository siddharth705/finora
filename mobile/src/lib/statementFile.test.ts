import * as DocumentPicker from 'expo-document-picker';
import * as appLock from './appLock';
import { pickStatement } from './statementFile';

jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));

const picker = DocumentPicker as jest.Mocked<typeof DocumentPicker>;

describe('pickStatement', () => {
  beforeEach(() => {
    picker.getDocumentAsync.mockReset();
    appLock.__resetSharingStateForTests();
  });

  // Bug found in review (Track D/D5): the native picker backgrounds this app the same way
  // Sharing.shareAsync does; without withShareSuppression, AppLockGate would show a spurious lock
  // prompt the instant the picker (or a provider like Drive/iCloud) returns focus here.
  it('suppresses AppLockGate for the duration of the picker call', async () => {
    picker.getDocumentAsync.mockImplementation(async () => {
      expect(appLock.isSharing()).toBe(true);
      return { canceled: true, assets: null };
    });

    expect(appLock.isSharing()).toBe(false);
    await pickStatement();
    expect(appLock.isSharing()).toBe(false);
  });
});
