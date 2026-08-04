import { describe, it, expect, vi, beforeEach } from 'vitest';
import { importApi } from './endpoints';
import { api } from './client';

vi.mock('./client', () => ({
  api: { post: vi.fn().mockResolvedValue({ data: {} }) },
  rawApi: { post: vi.fn() },
}));

// `api` is the mock factory's object literal above, not a real class instance, so there is no
// `this` to lose by pulling the spy out of it.
// eslint-disable-next-line @typescript-eslint/unbound-method
const post = vi.mocked(api.post);

/**
 * Where the statement password travels, asserted rather than assumed.
 *
 * A document password in a URL is captured by server access logs, proxy logs, browser history and
 * `Referer` headers -- all places it would sit in plain text long after the import finished. It has
 * to stay in the request body, and "someone simplifies this to a query param" is exactly the kind
 * of change that looks harmless in review, which is why it's pinned here.
 */
describe('importApi.stagePdf — where the password goes', () => {
  beforeEach(() => post.mockClear().mockResolvedValue({ data: {} } as never));

  function lastCall() {
    const [url, body] = post.mock.calls[0];
    return { url, form: body as FormData };
  }

  it('puts the password in the form body, never in the URL', async () => {
    await importApi.stagePdf(new File(['%PDF'], 'statement.pdf'), undefined, 'AAAA1234');

    const { url, form } = lastCall();
    expect(url).toBe('/import/pdf/stage');
    expect(url).not.toContain('AAAA1234');
    expect(url).not.toContain('?');
    expect(form.get('password')).toBe('AAAA1234');
  });

  it('omits the field entirely when no password was given', async () => {
    await importApi.stagePdf(new File(['%PDF'], 'statement.pdf'));

    // An unprotected upload sends exactly the body it always did -- the backend reads a missing
    // field and an empty one the same way, but sending nothing keeps the two cases honest on
    // the wire.
    expect(lastCall().form.has('password')).toBe(false);
  });

  it('omits an empty-string password rather than sending a blank field', async () => {
    await importApi.stagePdf(new File(['%PDF'], 'statement.pdf'), undefined, '');

    expect(lastCall().form.has('password')).toBe(false);
  });
});
