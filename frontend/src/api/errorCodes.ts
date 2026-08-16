/**
 * Backend error codes the UI must ACT on rather than merely display, from
 * com.finora.exception.ErrorCode. They arrive as `errorCode` on the standard ApiResponse envelope.
 *
 * Deliberately its own module rather than living in endpoints.ts: every test of a page that
 * branches on one of these mocks endpoints.ts wholesale, and a mock has to re-declare whatever it
 * exports. Re-declaring a wire constant means the test can pass against a value the app no longer
 * sends. Keeping them here, in a module with no imports of its own, lets those tests use the real
 * values while still mocking the API surface.
 */

// Two codes rather than one because the UI response differs. REQUIRED means we have not asked for
// a password yet, so the field opens. INVALID means the user gave one and the document rejected
// it, so the field stays open with what they typed still in it -- clearing it would read as though
// the app had lost the file.
export const PDF_PASSWORD_REQUIRED = 'IMPORT_008';
export const PDF_PASSWORD_INVALID = 'IMPORT_009';

// Added for the Premium Import Reliability v1 failure UX contract (Sprint 1, item 1). The backend
// already writes good, specific prose into `message` for each of these, but the contract exists so
// Finora controls the exact wording a user reads rather than depending on it -- see
// importFailureMessages.ts, the only module that reads these three.
export const NO_HEADER_DETECTED = 'IMPORT_001';
export const NO_TRANSACTIONS_FOUND = 'IMPORT_007';
export const SCANNED_OCR_REQUIRED = 'IMPORT_010';
export const CORRUPT_PDF = 'IMPORT_011';

// The UI must tell this apart from a genuinely expired/missing session -- reaching a completed
// job's "Review this import" action after the same session was already reviewed and confirmed
// through the normal flow used to show the generic expired-session message ("please upload the
// statement again"), which is actively wrong: the import already succeeded.
export const IMPORT_SESSION_ALREADY_CONFIRMED = 'IMPORT_012';

// Login.tsx branches on this to show a reactivation prompt instead of a dead-end error --
// distinct from a bare 403, the same reason every other code in this module exists. Bug fix: this
// used to be hand-typed independently in Login.tsx AND Login.test.tsx as the wrong value (the
// enum's Java NAME, 'AUTH_ACCOUNT_DEACTIVATED', instead of its wire CODE) -- both copies agreed
// with each other and both were wrong, so the whole test suite passed while the real feature was
// unreachable. This module exists specifically so a value like this has exactly one place to be
// wrong in.
export const AUTH_ACCOUNT_DEACTIVATED = 'AUTH_007';
