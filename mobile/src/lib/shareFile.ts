import { File } from 'expo-file-system';
import * as Sharing from 'expo-sharing';
import { withShareSuppression } from './appLock';

/**
 * The "check availability, share via the OS sheet, always clean up the cache file afterwards"
 * pattern shared by statementImportsApi.downloadFile, supportApi.downloadAttachment, and
 * reportExport.ts's shareCsv/sharePdf -- extracted after three independent, byte-for-byte copies
 * of it turned up in review (Track D security cleanup).
 *
 * `file` must already exist and hold the bytes to share. This always deletes it -- on a
 * successful share, a thrown share, or sharing being unavailable -- because in every one of those
 * three call sites the file existed purely to hand the share sheet a real URI and has no reason
 * to survive the attempt either way.
 */
export async function shareFileAndCleanUp(file: File, options: Sharing.SharingOptions): Promise<void> {
  try {
    if (!(await Sharing.isAvailableAsync())) {
      throw new Error('Sharing is not available on this device.');
    }
    // D5 (Track D security cleanup) -- see appLock.ts's withShareSuppression doc comment.
    await withShareSuppression(() => Sharing.shareAsync(file.uri, options));
  } finally {
    // D2 (Track D security cleanup, docs/project-management/plans/mobile-correctness-trust-roadmap.md).
    // Best-effort: a failed cleanup must not turn a completed (or already-failed) share into a new
    // user-facing error -- sweepFileCache's own startup/sign-out sweep is the backstop if this
    // doesn't run.
    try {
      file.delete();
    } catch {
      // See comment above.
    }
  }
}
