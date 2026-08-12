/**
 * The failure UX contract from the Premium Import Reliability v1 plan (§6) -- one curated message
 * per `ErrorCode`, so a user reads what Finora decided to say rather than whatever the server's
 * `message` field happened to contain. Extend this table, don't replace it, as new codes are added;
 * it is meant to be the single source of truth for user-facing import-failure copy.
 *
 * Imports its keys from errorCodes.ts rather than re-declaring the wire strings, so there is one
 * dictionary of import error codes, not two that can silently drift apart.
 */

import { NO_HEADER_DETECTED, NO_TRANSACTIONS_FOUND, SCANNED_OCR_REQUIRED } from './errorCodes';

export const IMPORT_FAILURE_MESSAGES: Record<string, string> = {
  [NO_HEADER_DETECTED]:
    "We couldn't find a transaction table in this file. Please check that you've uploaded the " +
    'transaction statement PDF from your bank, not a summary, terms document, or other export.',
  [NO_TRANSACTIONS_FOUND]:
    'We found a table in this statement but could not read any transactions from it. Please ' +
    'double-check this is the transaction statement PDF from your bank -- some other exports use ' +
    'a similar layout.',
  [SCANNED_OCR_REQUIRED]:
    'This PDF appears to be a scanned image rather than text. Statements exported directly from ' +
    "your bank's website usually work best.",
};
