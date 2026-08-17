import { AxiosError, AxiosHeaders } from 'axios';
import { apiErrorCode, apiErrorDetails, isOffline, toUserMessage } from './apiError';
import { PDF_PASSWORD_REQUIRED } from '../api/errorCodes';

function axiosErrorWithResponse(status: number, data: unknown): AxiosError {
  const err = new AxiosError('Request failed');
  err.response = {
    status,
    data,
    statusText: '',
    headers: new AxiosHeaders(),
    config: { headers: new AxiosHeaders() },
  } as AxiosError['response'];
  return err;
}

function networkError(): AxiosError {
  const err = new AxiosError('Network Error');
  err.code = 'ERR_NETWORK';
  return err; // no `response` -- the request never reached the server
}

const FALLBACK = 'Login failed. Check your credentials.';

describe('toUserMessage', () => {
  // The bug this module exists for: every screen used to fall through to its domain fallback on a
  // transport failure, telling users their credentials were wrong when they were simply offline.
  it('reports a transport failure as connectivity, not as the caller’s fallback', () => {
    const message = toUserMessage(networkError(), FALLBACK);
    expect(message).not.toBe(FALLBACK);
    expect(message).toMatch(/connection/i);
  });

  it('distinguishes a timeout from being offline', () => {
    const err = new AxiosError('timeout');
    err.code = 'ECONNABORTED';
    expect(toUserMessage(err, FALLBACK)).toMatch(/too long/i);
  });

  it('prefers the server’s own message', () => {
    const err = axiosErrorWithResponse(400, { message: 'That email is already registered.' });
    expect(toUserMessage(err, FALLBACK)).toBe('That email is already registered.');
  });

  it('ignores a blank server message', () => {
    const err = axiosErrorWithResponse(400, { message: '   ' });
    expect(toUserMessage(err, FALLBACK)).toBe(FALLBACK);
  });

  // Telling someone to check their credentials because the server threw sends them in circles.
  it('does not blame the user for a 5xx', () => {
    const message = toUserMessage(axiosErrorWithResponse(500, {}), FALLBACK);
    expect(message).not.toBe(FALLBACK);
    expect(message).toMatch(/our end/i);
  });

  it('uses the fallback for a 4xx with no message', () => {
    expect(toUserMessage(axiosErrorWithResponse(404, {}), FALLBACK)).toBe(FALLBACK);
  });

  // VerifyPhoneScreen awaits Firebase and the backend inside one try, so both error families
  // arrive at the same call site.
  it.each([
    ['auth/invalid-verification-code', /doesn.t match/i],
    ['auth/code-expired', /expired/i],
    ['auth/too-many-requests', /too many attempts/i],
    ['auth/network-request-failed', /connection/i],
  ])('maps Firebase %s to a specific message', (code, expected) => {
    expect(toUserMessage({ code }, 'Could not verify — try again.')).toMatch(expected);
  });

  it('falls back for an unrecognised Firebase code rather than leaking raw text', () => {
    expect(toUserMessage({ code: 'auth/some-new-code' }, 'Could not verify — try again.')).toBe(
      'Could not verify — try again.'
    );
  });

  it('falls back for a plain Error', () => {
    expect(toUserMessage(new Error('kaboom'), FALLBACK)).toBe(FALLBACK);
  });
});

describe('isOffline', () => {
  it('is true only when no response arrived', () => {
    expect(isOffline(networkError())).toBe(true);
    expect(isOffline(axiosErrorWithResponse(500, {}))).toBe(false);
    expect(isOffline(new Error('nope'))).toBe(false);
  });
});

describe('apiErrorCode', () => {
  it('returns the server error code so a screen can branch on it', () => {
    // The password prompt is the reason this exists: a protected PDF has to change what the
    // import screen SHOWS, which no amount of message text can drive.
    expect(apiErrorCode(axiosErrorWithResponse(422, { errorCode: PDF_PASSWORD_REQUIRED }))).toBe(
      PDF_PASSWORD_REQUIRED
    );
  });

  it('is null for anything without a server code, so callers fall through to a message', () => {
    expect(apiErrorCode(axiosErrorWithResponse(500, {}))).toBeNull();
    expect(apiErrorCode(networkError())).toBeNull();
    expect(apiErrorCode(new Error('kaboom'))).toBeNull();
    expect(apiErrorCode({ code: 'auth/code-expired' })).toBeNull();
  });
});

describe('apiErrorDetails', () => {
  it('returns the server error details so a screen can act on structured evidence', () => {
    // The reactivation token is the reason this exists: LoginScreen has to pull it out of the
    // envelope to drive the "Welcome back" prompt, not just display a message.
    expect(
      apiErrorDetails<{ reactivationToken: string }>(
        axiosErrorWithResponse(403, { errorCode: 'AUTH_007', details: { reactivationToken: 'tok-123' } })
      )
    ).toEqual({ reactivationToken: 'tok-123' });
  });

  it('is null for anything without a details payload', () => {
    expect(apiErrorDetails(axiosErrorWithResponse(500, {}))).toBeNull();
    expect(apiErrorDetails(axiosErrorWithResponse(400, { message: 'no details here' }))).toBeNull();
    expect(apiErrorDetails(networkError())).toBeNull();
    expect(apiErrorDetails(new Error('kaboom'))).toBeNull();
  });
});
