/**
 * The failure UX contract from the Premium Import Reliability v1 plan (§6) -- one curated message
 * per `ErrorCode`, so a user reads what Finora decided to say rather than whatever the server's
 * `message` field happened to contain. Extend this table, don't replace it, as new codes are added;
 * it is meant to be the single source of truth for user-facing import-failure copy.
 *
 * `actionRequired` lives alongside `message` in the same entry, not a second table keyed by the
 * same codes -- §1's `ACTION_REQUIRED` distinction (governing rule: "the user can reasonably
 * correct the input" vs "cannot fix it without Finora or support") is presentation metadata about
 * this exact failure, same as the message itself, and a second table would be exactly the kind of
 * "curated in two places, drifts apart" risk this codebase has been burned by before. Mirrors
 * `ErrorCode.userActionRequired()` on the backend for the sync path specifically -- the async path
 * (`ImportJobDto.Progress`/`Timeline`) gets this as `userStatus` straight from the wire instead,
 * since an `ImportJob` exists there to compute it; a sync upload never creates one, so the frontend
 * has to know the same answer itself.
 *
 * <b>A real drift risk, flagged rather than solved here.</b> `actionRequired` on each entry below
 * must exactly match `ErrorCode.userActionRequired()` for that code on the backend -- unlike
 * `message`, which is deliberately independent curated copy, this is a boolean CLASSIFICATION that
 * has to agree, and nothing today enforces that it does. Adding a code backend-side, or flipping an
 * existing one's flag, with nobody remembering to update the matching entry here would silently
 * mis-color a failure (most likely: a genuinely non-actionable failure rendered as if the user could
 * fix it). The async path already avoids this exact risk by computing `userStatus` once, backend-
 * side, and putting it on the wire; the same fix is possible here too -- have the sync error
 * envelope (`GlobalExceptionHandler`, which already holds the `ErrorCode` instance at serialization
 * time) carry `userActionRequired` alongside `errorCode`, and delete `actionRequired` from this file
 * entirely. Not done as part of Sprint 4 item 22, since it touches the generic error envelope every
 * endpoint uses, not just imports -- a bigger, separately-scoped change.
 *
 * Imports its keys from errorCodes.ts rather than re-declaring the wire strings, so there is one
 * dictionary of import error codes, not two that can silently drift apart.
 */

import { NO_HEADER_DETECTED, NO_TRANSACTIONS_FOUND, SCANNED_OCR_REQUIRED, CORRUPT_PDF } from './errorCodes';

export const IMPORT_FAILURE_MESSAGES: Record<string, { message: string; actionRequired: boolean }> = {
  [NO_HEADER_DETECTED]: {
    message:
      "We couldn't find a transaction table in this file. Please check that you've uploaded the " +
      'transaction statement PDF from your bank, not a summary, terms document, or other export.',
    actionRequired: true,
  },
  [NO_TRANSACTIONS_FOUND]: {
    message:
      'We found a table in this statement but could not read any transactions from it. Please ' +
      'double-check this is the transaction statement PDF from your bank -- some other exports use ' +
      'a similar layout.',
    actionRequired: true,
  },
  [SCANNED_OCR_REQUIRED]: {
    message:
      'This PDF appears to be a scanned image rather than text. Statements exported directly from ' +
      "your bank's website usually work best.",
    actionRequired: true,
  },
  [CORRUPT_PDF]: {
    message:
      'This file appears to be damaged or incomplete. Downloading it again from your bank usually ' +
      'fixes this.',
    // Unlike the three above, re-checking the upload doesn't help -- there's no single thing to
    // tell the user to change, so this stays plain FAILED (matches ErrorCode.IMPORT_CORRUPT_PDF's
    // own userActionRequired=false on the backend).
    actionRequired: false,
  },
};

/**
 * The one lookup step every consumer of the contract needs, shared rather than each page
 * reimplementing `code ? IMPORT_FAILURE_MESSAGES[code]?.message : undefined`. Deliberately does
 * NOT take a fallback string: Import.tsx's live-upload fallback (the server's own `message`, then
 * a PDF/CSV-specific generic string) and a historical record's fallback (no server message
 * available, one fixed generic string) are genuinely different, not two spellings of the same
 * thing -- each call site decides its own fallback from whatever `undefined` means to it.
 */
export function importFailureMessage(code: string | null | undefined): string | undefined {
  return code ? IMPORT_FAILURE_MESSAGES[code]?.message : undefined;
}

/**
 * Whether the user themselves can reasonably fix what caused this -- the sync-path counterpart to
 * the async path's `userStatus === 'ACTION_REQUIRED'`. Safe default `false` for any code with no
 * curated entry (including `null`/`undefined`), matching the backend's identical safe-default
 * philosophy (`ErrorCode.userActionRequiredOrDefault`): an uncurated failure has no known concrete
 * fix to offer, so it is never guessed into looking actionable.
 */
export function importFailureIsActionRequired(code: string | null | undefined): boolean {
  return code ? (IMPORT_FAILURE_MESSAGES[code]?.actionRequired ?? false) : false;
}
