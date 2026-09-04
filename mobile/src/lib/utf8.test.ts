import { decodeUtf8 } from './utf8';

function bytes(...values: number[]): ArrayBuffer {
  return new Uint8Array(values).buffer;
}

describe('decodeUtf8', () => {
  it('decodes plain ASCII', () => {
    expect(decodeUtf8(bytes(0x68, 0x69))).toBe('hi');
  });

  it('decodes multi-byte UTF-8 sequences (2, 3 and 4 byte)', () => {
    // Independent check: bytes produced by Node's own UTF-8 encoder, not by this codebase's own
    // encoder, so the assertion isn't just restating the implementation under test.
    expect(decodeUtf8(Uint8Array.from(Buffer.from('café', 'utf8')).buffer)).toBe('café');
    expect(decodeUtf8(Uint8Array.from(Buffer.from('日本語', 'utf8')).buffer)).toBe('日本語');
    expect(decodeUtf8(Uint8Array.from(Buffer.from('🎉', 'utf8')).buffer)).toBe('🎉');
  });

  it('decodes a JSON error envelope', () => {
    const json = JSON.stringify({ message: 'Statement is in object storage, but no provider is configured', errorCode: 'IMPORT_010' });
    expect(decodeUtf8(Uint8Array.from(Buffer.from(json, 'utf8')).buffer)).toBe(json);
  });

  it('decodes an empty buffer as an empty string', () => {
    expect(decodeUtf8(bytes())).toBe('');
  });
});
