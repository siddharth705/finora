/**
 * Just enough ZIP parsing to prove a downloaded archive contains the entries it claims to.
 *
 * Reads the central directory's file names only -- no decompression, no CRC check. That is all
 * the data-export test needs: proof that `goal_contributions.json` (and its siblings) actually
 * arrived in the browser's downloaded bytes, not just in DataExportService's own unit tests. A
 * full unzip (contents, not just names) would need either a real zip library or a lot more of
 * this file, and no test here asserts on file contents -- DataExportServiceTest already covers
 * that on the backend, in isolation, without a browser in the loop.
 *
 * No devDependency added for this: e2e/package.json carries exactly three packages today
 * (@playwright/test, pg, typescript), and the central directory format is small and stable enough
 * that hand-parsing it is less risk than pulling in a zip library for one test file.
 */

const EOCD_SIGNATURE = 0x06054b50;
const CENTRAL_DIRECTORY_SIGNATURE = 0x02014b50;
const EOCD_MIN_SIZE = 22;
const CENTRAL_DIRECTORY_HEADER_SIZE = 46;

/**
 * Finds the End Of Central Directory record and returns the names of every entry it points at.
 *
 * Assumes no ZIP comment (true for everything DataExportService.writeZip produces -- it never
 * sets one), so the EOCD record is exactly the last 22 bytes. A commented ZIP would need a
 * backward scan for the signature instead; that case doesn't exist here.
 */
export function listZipEntryNames(zip: Buffer): string[] {
  if (zip.length < EOCD_MIN_SIZE) {
    throw new Error(`Not a ZIP file: only ${zip.length} bytes, shorter than a minimal EOCD record.`);
  }

  const eocd = zip.subarray(zip.length - EOCD_MIN_SIZE);
  if (eocd.readUInt32LE(0) !== EOCD_SIGNATURE) {
    throw new Error('Not a ZIP file (or has a comment this parser does not handle): EOCD signature not found at the expected offset.');
  }

  const totalEntries = eocd.readUInt16LE(10);
  const centralDirectoryOffset = eocd.readUInt32LE(16);

  const names: string[] = [];
  let pos = centralDirectoryOffset;
  for (let i = 0; i < totalEntries; i++) {
    if (zip.readUInt32LE(pos) !== CENTRAL_DIRECTORY_SIGNATURE) {
      throw new Error(`Central directory entry ${i} at offset ${pos} does not start with the expected signature.`);
    }
    const nameLength = zip.readUInt16LE(pos + 28);
    const extraLength = zip.readUInt16LE(pos + 30);
    const commentLength = zip.readUInt16LE(pos + 32);
    const nameStart = pos + CENTRAL_DIRECTORY_HEADER_SIZE;
    names.push(zip.subarray(nameStart, nameStart + nameLength).toString('utf8'));
    pos = nameStart + nameLength + extraLength + commentLength;
  }

  return names;
}
