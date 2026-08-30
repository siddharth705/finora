import axios from 'axios';

/**
 * One place that turns any thrown error into something worth showing a user.
 *
 * Every screen previously did `err.response?.data?.message ?? 'Login failed…'` inline. That reads
 * fine until the request never reached the server at all: axios leaves `response` undefined on a
 * network failure, so every one of those fell through to its domain fallback and told the user
 * their credentials were wrong, or that a delete failed, when the real cause was no connectivity.
 * On mobile that's not an edge case -- it's a tunnel.
 *
 * Order matters here: transport failures are checked before the server envelope, because an error
 * with no response can't have a server message, and the server's own message always beats a
 * client-side guess when there is one.
 */

// Firebase Auth surfaces its own codes, which reach the same catch blocks as API errors (see
// VerifyPhoneScreen, which awaits Firebase and then the backend in one try). Only the cases worth
// distinguishing are mapped -- anything else falls through rather than showing raw Firebase text.
const FIREBASE_MESSAGES: Record<string, string> = {
  'auth/invalid-verification-code': "That code doesn't match — check and try again.",
  'auth/code-expired': 'This code has expired. Request a new one.',
  'auth/session-expired': 'This code has expired. Request a new one.',
  'auth/invalid-phone-number': "That phone number doesn't look right.",
  'auth/too-many-requests': 'Too many attempts. Wait a few minutes before trying again.',
  'auth/quota-exceeded': 'Verification is temporarily unavailable. Try again shortly.',
  'auth/network-request-failed': "Can't reach the verification service. Check your connection.",
  'auth/missing-client-identifier':
    'This build can’t verify your device. Its Firebase setup is incomplete — see docs/engineering/mobile-setup.md.',
};

const OFFLINE_MESSAGE = "Can't reach Fynora. Check your connection and try again.";
const TIMEOUT_MESSAGE = 'That took too long. Check your connection and try again.';

/** True when the request never got a response -- offline, DNS failure, connection refused. */
export function isOffline(err: unknown): boolean {
  if (!axios.isAxiosError(err)) return false;
  return !err.response && err.code !== 'ECONNABORTED';
}

/**
 * The server's structured error code (see src/api/errorCodes.ts), or null for anything that isn't
 * an answered API error. Separate from toUserMessage because these two do different jobs: that one
 * decides what to SAY, this one decides what to DO -- a code like a password prompt changes the
 * screen rather than printing a line of text.
 */
export function apiErrorCode(err: unknown): string | null {
  if (!axios.isAxiosError(err) || !err.response) return null;
  const code = (err.response.data as { errorCode?: unknown } | undefined)?.errorCode;
  return typeof code === 'string' ? code : null;
}

/**
 * The server's error-specific payload (see ApiException's `details` map on the backend), or null
 * for anything that isn't an answered API error. Same shape of accessor as apiErrorCode() above,
 * for the same reason: a caller that needs to branch UI on structured evidence -- e.g.
 * AUTH_ACCOUNT_DEACTIVATED's reactivation token -- shouldn't have to hand-write its own
 * axios-error type cast to reach it.
 */
export function apiErrorDetails<T = unknown>(err: unknown): T | null {
  if (!axios.isAxiosError(err) || !err.response) return null;
  const details = (err.response.data as { details?: unknown } | undefined)?.details;
  return details == null ? null : (details as T);
}

export function toUserMessage(err: unknown, fallback: string): string {
  // Firebase errors aren't axios errors and carry their own `code`.
  const code = (err as { code?: unknown } | null)?.code;
  if (typeof code === 'string' && code.startsWith('auth/')) {
    return FIREBASE_MESSAGES[code] ?? fallback;
  }

  if (axios.isAxiosError(err)) {
    if (err.code === 'ECONNABORTED') return TIMEOUT_MESSAGE;
    if (!err.response) return OFFLINE_MESSAGE;

    // client.ts's response interceptor normalizes error bodies to { message, errorCode }, so the
    // server's own wording -- which is written for users and knows the actual domain rule that
    // failed -- is preferred over anything guessed here.
    const message = (err.response.data as { message?: unknown } | undefined)?.message;
    if (typeof message === 'string' && message.trim()) return message;

    // A 5xx with no body is the server's problem, and telling someone to check their input for it
    // just sends them in circles.
    if (err.response.status >= 500) return 'Something went wrong on our end. Try again shortly.';
  }

  return fallback;
}
