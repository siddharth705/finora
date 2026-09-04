/**
 * Decodes UTF-8 bytes into a string.
 *
 * Written out rather than reached for `TextDecoder`: Hermes does not ship a spec-compliant
 * TextDecoder without an extra polyfill dependency this project doesn't have (see
 * facebook/hermes#1403) -- same reasoning as `encodeBase64` in base64.ts avoiding `btoa`.
 *
 * Used by statementImportsApi.downloadFile's error handling: axios on React Native hands back an
 * ArrayBuffer even for a failed request's JSON error body (responseType: 'arraybuffer' applies to
 * error responses too), so the envelope has to be decoded back into text before it can be parsed.
 */
export function decodeUtf8(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let out = '';
  let i = 0;

  while (i < bytes.length) {
    const b0 = bytes[i];

    if (b0 < 0x80) {
      out += String.fromCharCode(b0);
      i += 1;
    } else if (b0 >> 5 === 0b110) {
      const cp = ((b0 & 0x1f) << 6) | (bytes[i + 1] & 0x3f);
      out += String.fromCharCode(cp);
      i += 2;
    } else if (b0 >> 4 === 0b1110) {
      const cp = ((b0 & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f);
      out += String.fromCharCode(cp);
      i += 3;
    } else {
      // 4-byte sequence -- decodes to a codepoint above the BMP, which JS strings represent as a
      // UTF-16 surrogate pair rather than one `String.fromCharCode` call.
      const cp = ((b0 & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f);
      const adjusted = cp - 0x10000;
      out += String.fromCharCode(0xd800 + (adjusted >> 10), 0xdc00 + (adjusted & 0x3ff));
      i += 4;
    }
  }

  return out;
}
