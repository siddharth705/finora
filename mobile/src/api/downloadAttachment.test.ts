import { supportApi } from './endpoints';
import { api } from './client';

jest.mock('./client', () => ({
  api: { get: jest.fn() },
  rawApi: { post: jest.fn() },
}));

jest.mock('expo-file-system', () => ({
  File: jest.fn().mockImplementation(() => ({
    exists: false,
    delete: jest.fn(),
    create: jest.fn(),
    write: jest.fn(),
    uri: 'file:///mock/attachment.pdf',
  })),
  Paths: { cache: 'mock-cache-dir' },
}));

jest.mock('expo-sharing', () => ({
  isAvailableAsync: jest.fn().mockResolvedValue(true),
  shareAsync: jest.fn().mockResolvedValue(undefined),
}));

const get = api.get as jest.Mock;

function arrayBufferError(status: number, body: unknown) {
  const json = JSON.stringify(body);
  const bytes = Uint8Array.from(Buffer.from(json, 'utf8'));
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status, data: bytes.buffer },
  });
}

/**
 * Same bug as statementImportsApi.downloadFile (see downloadFile.test.ts): responseType:
 * 'arraybuffer' applies to error responses too, so a failed attachment download's
 * error.response.data arrives as a raw ArrayBuffer rather than the parsed {message, errorCode}
 * envelope every other caller expects.
 */
describe('supportApi.downloadAttachment — error message survives a failed download', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('decodes the ArrayBuffer error body back into the real server message', async () => {
    get.mockRejectedValue(arrayBufferError(404, {
      message: 'This attachment no longer exists.',
      errorCode: 'SUPPORT_ATTACHMENT_NOT_FOUND',
    }));

    await expect(
      supportApi.downloadAttachment('ticket-1', 'attach-1', 'receipt.png', 'image/png')
    ).rejects.toMatchObject({
      response: {
        data: {
          message: 'This attachment no longer exists.',
          errorCode: 'SUPPORT_ATTACHMENT_NOT_FOUND',
        },
      },
    });
  });

  it('falls back to a readable message when the error body is not JSON', async () => {
    const bytes = Uint8Array.from(Buffer.from('<html>Bad Gateway</html>', 'utf8'));
    get.mockRejectedValue(Object.assign(new Error('Request failed'), {
      isAxiosError: true,
      response: { status: 502, data: bytes.buffer },
    }));

    await expect(
      supportApi.downloadAttachment('ticket-1', 'attach-1', 'receipt.png', 'image/png')
    ).rejects.toMatchObject({
      response: {
        data: {
          message: expect.any(String),
        },
      },
    });
  });
});
