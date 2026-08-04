const ALPHABET = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/';

/**
 * Base64-encodes raw bytes.
 *
 * Written out rather than reached for from a global or a dependency. React Native does expose
 * `btoa`, but it takes a *binary string*, so using it means first building a string as long as the
 * file out of char codes -- and its presence is a property of the runtime rather than of anything
 * this project declares. A statement can be up to 10 MB (UPLOAD_MAX_FILE_SIZE), so neither the
 * intermediate string nor an undeclared global is worth it when the encoder is fifteen lines and
 * can be tested directly.
 *
 * Used by statementImportsApi.downloadFile: expo-file-system's write() takes a string, and axios
 * on React Native hands back an ArrayBuffer with no Blob to convert through.
 */
export function encodeBase64(buffer: ArrayBuffer): string {
  const bytes = new Uint8Array(buffer);
  let out = '';

  // Three bytes -> four characters. The tail (1 or 2 leftover bytes) is padded with '=' below.
  for (let i = 0; i < bytes.length; i += 3) {
    const b0 = bytes[i];
    const b1 = bytes[i + 1];
    const b2 = bytes[i + 2];

    out += ALPHABET[b0 >> 2];
    out += ALPHABET[((b0 & 0x03) << 4) | ((b1 ?? 0) >> 4)];
    out += b1 === undefined ? '=' : ALPHABET[((b1 & 0x0f) << 2) | ((b2 ?? 0) >> 6)];
    out += b2 === undefined ? '=' : ALPHABET[b2 & 0x3f];
  }

  return out;
}
