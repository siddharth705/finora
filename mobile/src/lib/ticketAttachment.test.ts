import * as DocumentPicker from 'expo-document-picker';
import { AttachmentTooLargeError, pickTicketAttachment } from './ticketAttachment';

jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));

const picker = DocumentPicker as jest.Mocked<typeof DocumentPicker>;

describe('pickTicketAttachment', () => {
  beforeEach(() => {
    picker.getDocumentAsync.mockReset();
  });

  it('returns null when the user cancels -- a cancel is not an error', async () => {
    picker.getDocumentAsync.mockResolvedValue({ canceled: true, assets: null });

    expect(await pickTicketAttachment()).toBeNull();
  });

  it('returns an RNFile descriptor for a valid pick', async () => {
    picker.getDocumentAsync.mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file:///cache/screenshot.png', name: 'screenshot.png', size: 2048, mimeType: 'image/png', lastModified: 0 }],
    });

    expect(await pickTicketAttachment()).toEqual({
      uri: 'file:///cache/screenshot.png', name: 'screenshot.png', type: 'image/png',
    });
  });

  it('throws AttachmentTooLargeError for a file over 5 MB, without ever asking the caller to inspect size themselves', async () => {
    picker.getDocumentAsync.mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file:///cache/big.pdf', name: 'big.pdf', size: 6 * 1024 * 1024, mimeType: 'application/pdf', lastModified: 0 }],
    });

    await expect(pickTicketAttachment()).rejects.toThrow(AttachmentTooLargeError);
  });

  it('falls back to a generic name/type when the provider reports neither', async () => {
    picker.getDocumentAsync.mockResolvedValue({
      canceled: false,
      assets: [{ uri: 'file:///cache/x', name: undefined as unknown as string, size: undefined, mimeType: undefined, lastModified: 0 }],
    });

    expect(await pickTicketAttachment()).toEqual({
      uri: 'file:///cache/x', name: 'attachment', type: 'application/octet-stream',
    });
  });
});
