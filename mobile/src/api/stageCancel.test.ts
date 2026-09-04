import axios from 'axios';
import { importApi } from './endpoints';
import { api } from './client';
import { isCanceled, isOffline } from '../lib/apiError';

jest.mock('./client', () => ({
  api: { post: jest.fn() },
  rawApi: { post: jest.fn() },
}));

const post = api.post as jest.Mock;

const file = { uri: 'file:///statement.csv', name: 'statement.csv', type: 'text/csv' };

/** What axios actually throws when an AbortController fires: no response, code ERR_CANCELED. */
function cancelError() {
  return Object.assign(new Error('canceled'), {
    isAxiosError: true,
    code: 'ERR_CANCELED',
    toJSON: () => ({}),
  });
}

/** A genuine connection failure: also no response, but not a cancel. */
function offlineError() {
  return Object.assign(new Error('Network Error'), {
    isAxiosError: true,
    code: 'ERR_NETWORK',
    toJSON: () => ({}),
  });
}

beforeEach(() => post.mockReset());

describe('a cancelled upload is not mistaken for a network failure', () => {
  it('isOffline cannot tell them apart — which is why isCanceled has to be asked first', () => {
    // Pinning the trap itself, not just the fix. A cancelled request has no `response`, so it
    // satisfies isOffline's "never got a response" test exactly the way a dead connection does.
    // Every retry-on-offline path is therefore a retry-on-cancel path unless it checks this first.
    expect(isOffline(cancelError())).toBe(true);
    expect(isCanceled(cancelError())).toBe(true);
    expect(isCanceled(offlineError())).toBe(false);
    expect(axios.isAxiosError(cancelError())).toBe(true);
  });

  it('does not re-upload the file after the user cancels', async () => {
    // The bug this prevents: stageWithRetry retries once on isOffline. Without the cancel check it
    // retried the very upload just cancelled, so the file went up a second time and the Cancel
    // button appeared to do nothing at all.
    post.mockRejectedValue(cancelError());

    await expect(importApi.stageCsv(file)).rejects.toMatchObject({ code: 'ERR_CANCELED' });

    expect(post).toHaveBeenCalledTimes(1);
  });

  it('still retries once on a real connection failure', async () => {
    // The behaviour the cancel check must not break -- stageWithRetry exists for a real timing gap
    // right after the file picker returns (see its own doc comment).
    post
      .mockRejectedValueOnce(offlineError())
      .mockResolvedValueOnce({ data: { sessionId: 's-1', staging: {} } });

    await expect(importApi.stageCsv(file)).resolves.toMatchObject({ sessionId: 's-1' });

    expect(post).toHaveBeenCalledTimes(2);
  });
});

describe('the abort signal reaches axios', () => {
  it('passes the caller signal through on a CSV upload', async () => {
    post.mockResolvedValue({ data: {} });
    const controller = new AbortController();

    await importApi.stageCsv(file, undefined, controller.signal);

    expect(post.mock.calls[0][2]).toMatchObject({ signal: controller.signal });
  });

  it('passes it through on a PDF upload, alongside the password', async () => {
    post.mockResolvedValue({ data: {} });
    const controller = new AbortController();

    await importApi.stagePdf(file, undefined, 'hunter2', controller.signal);

    expect(post.mock.calls[0][2]).toMatchObject({ signal: controller.signal });
  });

  it('keeps timeout:0 — the upload must not gain a deadline just because it can now be cancelled', async () => {
    // Deliberate and documented: a slow-connection upload can legitimately exceed the 30s default,
    // and it already shows live progress. Cancellation is the way out, not a timeout.
    post.mockResolvedValue({ data: {} });

    await importApi.stageCsv(file, undefined, new AbortController().signal);

    expect(post.mock.calls[0][2]).toMatchObject({ timeout: 0 });
  });

  it('omits the signal entirely when the caller passes none', async () => {
    post.mockResolvedValue({ data: {} });

    await importApi.stageCsv(file);

    expect(post.mock.calls[0][2]).not.toHaveProperty('signal');
  });
});
