/**
 * The failure UX contract from the Premium Import Reliability v1 plan (§6) -- one curated message
 * per `ErrorCode`, so a user reads what Finora decided to say rather than whatever the server's
 * `message` field happened to contain. Extend this table, don't replace it, as new codes are added;
 * it is meant to be the single source of truth for user-facing import-failure copy.
 *
 * Deliberately does NOT also carry `ACTION_REQUIRED`-ness alongside the message, even though an
 * earlier version of this file did: that would have been a boolean CLASSIFICATION duplicating
 * `ErrorCode.userActionRequired()` on the backend, a real drift risk unlike the message text
 * (which is deliberately independent curated copy, not something meant to match the backend at
 * all). `userActionRequired` comes off the wire instead -- `GlobalExceptionHandler` merges
 * `ErrorCode.userActionRequired()` into every `ApiException` response's `details`, and
 * `client.ts`'s response interceptor surfaces it as `error.response.data.userActionRequired` --
 * matching how the async path already gets the identical answer as `userStatus`, computed once,
 * backend-side, rather than re-derived here.
 *
 * Imports its keys from errorCodes.ts rather than re-declaring the wire strings, so there is one
 * dictionary of import error codes, not two that can silently drift apart.
 */

import {
  NO_HEADER_DETECTED,
  NO_TRANSACTIONS_FOUND,
  NO_ACTIVITY_IN_PERIOD,
  SCANNED_OCR_REQUIRED,
  CORRUPT_PDF,
} from './errorCodes';

export const IMPORT_FAILURE_MESSAGES: Record<string, string> = {
  [NO_HEADER_DETECTED]:
    "We couldn't find a transaction table in this file. Please check that you've uploaded the " +
    'transaction statement PDF from your bank, not a summary, terms document, or other export.',
  [NO_TRANSACTIONS_FOUND]:
    'We found a table in this statement but could not read any transactions from it. Please ' +
    'double-check this is the transaction statement PDF from your bank -- some other exports use ' +
    'a similar layout.',
  // Deliberately different in KIND from every other message in this table, not just wording: this
  // is the one code here that is not describing something Finora failed to do. The statement's own
  // printed summary says there was no activity, so there is nothing wrong with the file or with
  // Finora's reading of it -- see errorCodes.ts's own comment. It stays in this table (rather than
  // a separate "informational" dictionary) because the banner mechanics -- amber via
  // userActionRequired, not red -- are shared with every other code here; only the copy differs.
  [NO_ACTIVITY_IN_PERIOD]:
    "This statement's own summary shows no transactions for the period it covers, so there's " +
    'nothing to import from it.',
  [SCANNED_OCR_REQUIRED]:
    'This PDF appears to be a scanned image rather than text. Statements exported directly from ' +
    "your bank's website usually work best.",
  [CORRUPT_PDF]:
    'This file appears to be damaged or incomplete. Downloading it again from your bank usually ' +
    'fixes this.',
};

/**
 * The one lookup step every consumer of the contract needs, shared rather than each page
 * reimplementing `code ? IMPORT_FAILURE_MESSAGES[code] : undefined`. Deliberately does NOT take a
 * fallback string: Import.tsx's live-upload fallback (the server's own `message`, then a PDF/CSV-
 * specific generic string) and a historical record's fallback (no server message available, one
 * fixed generic string) are genuinely different, not two spellings of the same thing -- each call
 * site decides its own fallback from whatever `undefined` means to it.
 */
export function importFailureMessage(code: string | null | undefined): string | undefined {
  return code ? IMPORT_FAILURE_MESSAGES[code] : undefined;
}
