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
