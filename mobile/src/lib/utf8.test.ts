import { decodeUtf8, encodeUtf8 } from './utf8';

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

// D4 (Track D security cleanup). queryCacheCipher.ts needs this direction: expo-crypto's
// aesEncryptAsync takes raw bytes, not a JS string, for the JSON payload being encrypted.
describe('encodeUtf8', () => {
  it('encodes plain ASCII', () => {
    expect(Array.from(encodeUtf8('hi'))).toEqual([0x68, 0x69]);
  });

  it('matches Node\'s own UTF-8 encoder for 2, 3 and 4 byte sequences', () => {
    // Independent check, same posture as decodeUtf8's own tests: the oracle is Node's encoder,
    // not this codebase's decoder, so a bug shared between encode and decode can't hide.
    expect(Array.from(encodeUtf8('café'))).toEqual(Array.from(Buffer.from('café', 'utf8')));
    expect(Array.from(encodeUtf8('日本語'))).toEqual(Array.from(Buffer.from('日本語', 'utf8')));
    expect(Array.from(encodeUtf8('🎉'))).toEqual(Array.from(Buffer.from('🎉', 'utf8')));
  });

  it('encodes an empty string as an empty array', () => {
    expect(encodeUtf8('').length).toBe(0);
  });

  it('round-trips through decodeUtf8 for a realistic JSON payload with mixed scripts and an emoji', () => {
    const json = JSON.stringify({ merchant: 'Café Nero', note: '日本語 receipt 🎉', amount: -450 });
    expect(decodeUtf8(encodeUtf8(json).buffer)).toBe(json);
  });
});
