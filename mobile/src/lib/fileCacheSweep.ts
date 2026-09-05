import { Directory, File, Paths } from 'expo-file-system';

/**
 * D2 (Track D security cleanup, docs/project-management/plans/mobile-correctness-trust-roadmap.md).
 * A backstop, not the primary fix -- statementImportsApi.downloadFile, supportApi.downloadAttachment,
 * and reportExport's shareCsv/sharePdf each delete their own file right after Sharing.shareAsync()
 * resolves. This exists for what per-site cleanup can't cover: pickStatement and
 * pickTicketAttachment copy the picked file into this same cache directory purely to hand the
 * upload a URI the picker's own provider (Drive, iCloud) might otherwise revoke -- the file is
 * never shared, so there is no shareAsync completion to hang cleanup off of, and no clean
 * "upload finished" hook either without threading cleanup through every screen that calls them.
 * It also catches anything the per-site cleanup missed (the app killed mid-share, a delete() call
 * itself failing).
 *
 * Age-based, not name-pattern-based: the picked file keeps the user's own original filename (no
 * "fynora-" prefix to match on the way reportExport's own exports have), so age is the only
 * reliable signal. Nothing this app writes to the top level of the cache directory is meant to
 * outlive one upload or share flow, so an hour is a generous margin, not a tight one.
 */
const MAX_AGE_MS = 60 * 60 * 1000;

/** `now` is injectable (defaults to the real clock) purely for the test below -- the jest mock
 *  for expo-file-system deliberately stamps `lastModified` from its own logical clock, not
 *  `Date.now()` (see its own module doc comment), so a test proving "old enough gets deleted,
 *  young enough survives" has to compare against a `now` it controls, not real wall-clock time. */
export function sweepFileCache(now: number = Date.now()): void {
  let entries: (Directory | File)[];
  try {
    entries = new Directory(Paths.cache).list();
  } catch {
    return; // Nothing to sweep, or the directory itself is unreadable -- not fatal either way.
  }

  const cutoff = now - MAX_AGE_MS;
  for (const entry of entries) {
    // Subdirectories are left alone -- every writer above puts a plain file directly at the top
    // level, so a subdirectory here belongs to something else (a library's own cache) that
    // manages its own lifetime.
    if (!(entry instanceof File)) continue;
    try {
      const modifiedAt = entry.lastModified;
      if (modifiedAt !== null && modifiedAt < cutoff) {
        entry.delete();
      }
    } catch {
      // Best-effort: one unreadable or already-gone entry must not stop the rest of the sweep.
    }
  }
}
