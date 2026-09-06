import { File } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { statementImportsApi } from './endpoints';
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
    uri: 'file:///mock/statement.pdf',
  })),
  Paths: { cache: 'mock-cache-dir' },
}));

jest.mock('expo-sharing', () => ({
  isAvailableAsync: jest.fn().mockResolvedValue(true),
  shareAsync: jest.fn().mockResolvedValue(undefined),
}));

const get = api.get as jest.Mock;
const MockedFile = File as unknown as jest.Mock;
const shareAsync = Sharing.shareAsync as jest.Mock;

/** The one File instance downloadFile constructs for this call -- `new File(...)` is a mock
 *  factory returning a fresh plain object each invocation, so assertions on `.delete` have to
 *  go through the specific instance the code under test actually got, not a call made before it. */
function lastFileInstance() {
  return MockedFile.mock.results[MockedFile.mock.results.length - 1].value;
}

function arrayBufferError(status: number, body: unknown) {
  const json = JSON.stringify(body);
  const bytes = Uint8Array.from(Buffer.from(json, 'utf8'));
  return Object.assign(new Error('Request failed'), {
    isAxiosError: true,
    response: { status, data: bytes.buffer },
  });
}

/**
 * responseType: 'arraybuffer' applies to error responses too, so a failed download's
 * error.response.data arrives as a raw ArrayBuffer rather than the parsed {message, errorCode}
 * envelope every other caller in the app expects -- the same problem the web app's
 * withBlobErrorMessage fixes for its Blob-typed downloads.
 */
describe('statementImportsApi.downloadFile — error message survives a failed download', () => {
  beforeEach(() => {
    get.mockReset();
  });

  it('decodes the ArrayBuffer error body back into the real server message', async () => {
    get.mockRejectedValue(arrayBufferError(500, {
      message: 'Statement stmt-1 is in object storage, but no storage provider is configured',
      errorCode: 'IMPORT_010',
    }));

    await expect(statementImportsApi.downloadFile('stmt-1', 'statement.pdf')).rejects.toMatchObject({
      response: {
        data: {
          message: 'Statement stmt-1 is in object storage, but no storage provider is configured',
          errorCode: 'IMPORT_010',
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

    await expect(statementImportsApi.downloadFile('stmt-1', 'statement.pdf')).rejects.toMatchObject({
      response: {
        data: {
          message: expect.any(String),
        },
      },
    });
  });
});

/**
 * D2 (Track D security cleanup). Decrypted statement bytes only ever existed on disk to hand the
 * OS share sheet a real URI -- once shareAsync settles, the file has to go, whether the share
 * itself succeeded, was dismissed, or the share call threw.
 */
describe('statementImportsApi.downloadFile — cleans up the cache file after sharing', () => {
  beforeEach(() => {
    get.mockReset().mockResolvedValue({ data: new Uint8Array([1, 2, 3]).buffer });
    shareAsync.mockReset();
  });

  it('deletes the cache file after a successful share', async () => {
    shareAsync.mockResolvedValue(undefined);

    await statementImportsApi.downloadFile('stmt-1', 'statement.pdf');

    expect(lastFileInstance().delete).toHaveBeenCalled();
  });

  it('still deletes the cache file when the share itself throws', async () => {
    shareAsync.mockRejectedValue(new Error('share sheet dismissed with an error'));

    await expect(statementImportsApi.downloadFile('stmt-1', 'statement.pdf')).rejects.toThrow();

    expect(lastFileInstance().delete).toHaveBeenCalled();
  });

  it('does not let a failed cleanup mask a successful share', async () => {
    shareAsync.mockResolvedValue(undefined);
    MockedFile.mockImplementationOnce(() => ({
      exists: false,
      delete: jest.fn(() => { throw new Error('already gone'); }),
      create: jest.fn(),
      write: jest.fn(),
      uri: 'file:///mock/statement.pdf',
    }));

    await expect(statementImportsApi.downloadFile('stmt-1', 'statement.pdf')).resolves.toBeUndefined();
  });
});
