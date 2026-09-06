import * as DocumentPicker from 'expo-document-picker';
import * as appLock from './appLock';
import type { RNFile } from '../api/endpoints';

/**
 * Picking an optional attachment for a new support ticket. Same shape as pickStatement()
 * (lib/statementFile.ts) for the same reason: RNFile is what endpoints.ts's FormData upload
 * takes, and expo-document-picker already returns almost exactly that.
 *
 * Mirrors SupportAttachmentUpload's own allow-list and size ceiling on the backend (PDF, PNG,
 * JPEG, or plain text; 5 MB) -- this is a convenience so most users never hit the server's
 * 400/415 at all, not a substitute for it: the server re-validates every byte regardless of what
 * the picker let through.
 */

const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
const ACCEPTED_MIME = ['application/pdf', 'image/png', 'image/jpeg', 'text/plain'];

export class AttachmentTooLargeError extends Error {}

/** Returns null when the user dismisses the picker -- a cancel is not an error. Throws only for
 *  a genuinely unusable selection (over size), so the caller can show that message. */
export async function pickTicketAttachment(): Promise<RNFile | null> {
  // Bug found in review (Track D/D5): same as pickStatement() -- the native picker backgrounds
  // this app, and without this suppression AppLockGate would show a spurious lock prompt the
  // instant the picker (or a provider like Drive/iCloud) returns focus here.
  const result = await appLock.withShareSuppression(() => DocumentPicker.getDocumentAsync({
    type: ACCEPTED_MIME,
    // Same reasoning as pickStatement(): without this the URI can point into a provider (Drive,
    // iCloud) the upload cannot read, or one that's revoked the moment the picker closes.
    copyToCacheDirectory: true,
    multiple: false,
  }));

  if (result.canceled || !result.assets?.length) return null;

  const asset = result.assets[0];
  if (asset.size != null && asset.size > MAX_ATTACHMENT_BYTES) {
    throw new AttachmentTooLargeError('Attachments are limited to 5 MB.');
  }

  return {
    uri: asset.uri,
    name: asset.name ?? 'attachment',
    type: asset.mimeType ?? 'application/octet-stream',
  };
}
