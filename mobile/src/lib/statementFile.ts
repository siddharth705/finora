import * as DocumentPicker from 'expo-document-picker';
import type { RNFile } from '../api/endpoints';

/**
 * Picking a statement to import.
 *
 * The web app drops a file onto a drag-and-drop zone and hands the resulting `File` to axios. None
 * of that exists here: there is no drag target on a phone, and React Native's FormData takes a
 * plain `{uri, name, type}` descriptor rather than a `File`. `expo-document-picker` returns almost
 * exactly that descriptor, which is why endpoints.ts types its upload arguments as RNFile.
 */

export type StatementFormat = 'CSV' | 'PDF';

export interface PickedStatement {
  file: RNFile;
  format: StatementFormat;
}

/** iOS matches on UTIs, Android on MIME types, and some providers report a CSV as text/plain or
 *  even application/octet-stream. The extension check below is what actually decides. */
const ACCEPTED_MIME = ['text/csv', 'text/comma-separated-values', 'application/pdf', 'text/plain'];

/**
 * Returns null when the user dismisses the picker -- a cancel is not an error and must not surface
 * one. Throws only for a genuinely unusable selection, so callers can show that message.
 */
export async function pickStatement(): Promise<PickedStatement | null> {
  const result = await DocumentPicker.getDocumentAsync({
    type: ACCEPTED_MIME,
    // Copies the file into the app's cache directory. Without this the URI can point into a
    // provider (Drive, iCloud) that the upload cannot read, or that is revoked the moment the
    // picker closes -- which fails later, during upload, where the cause is far less obvious.
    copyToCacheDirectory: true,
    multiple: false,
  });

  if (result.canceled || !result.assets?.length) return null;

  const asset = result.assets[0];
  const name = asset.name ?? 'statement';
  const lower = name.toLowerCase();

  // Decided by extension, not the reported MIME type, because providers are unreliable about it --
  // a CSV routinely arrives as text/plain or application/octet-stream. The backend has separate
  // /import/csv/stage and /import/pdf/stage endpoints, so this has to be right.
  const format: StatementFormat | null = lower.endsWith('.pdf')
    ? 'PDF'
    : lower.endsWith('.csv')
      ? 'CSV'
      : null;

  if (!format) {
    throw new Error('Choose a .csv or .pdf bank or credit card statement.');
  }

  return {
    file: {
      uri: asset.uri,
      name,
      type: format === 'PDF' ? 'application/pdf' : 'text/csv',
    },
    format,
  };
}
