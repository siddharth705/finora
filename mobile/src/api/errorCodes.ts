/**
 * Backend error codes the UI must ACT on rather than merely display, from
 * com.finora.exception.ErrorCode. client.ts's response interceptor normalizes them onto the error
 * body as `errorCode`.
 *
 * Its own module, with no imports, so a screen test that mocks the API surface can still assert
 * against the real wire values instead of re-declaring them in a mock -- a re-declared constant
 * lets a test keep passing against a code the app no longer sends. Duplicated from the web app's
 * src/api/errorCodes.ts on purpose: two four-line constant files cost less than a shared package
 * between a Vite app and a Metro app.
 */

// Two codes rather than one because the response differs. REQUIRED means we have not asked for a
// password yet, so the field opens. INVALID means the user gave one and the document rejected it,
// so the field stays open with what they typed still in it.
export const PDF_PASSWORD_REQUIRED = 'IMPORT_008';
export const PDF_PASSWORD_INVALID = 'IMPORT_009';
