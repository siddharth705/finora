import { importApi } from './endpoints';
import { api } from './client';

jest.mock('./client', () => ({
  api: { post: jest.fn() },
  rawApi: { post: jest.fn() },
}));

const post = api.post as jest.Mock;

/**
 * Where the statement password travels, asserted rather than assumed. Mirrors the web app's
 * src/api/stagePdf.test.ts -- the invariant is the same on both clients, and the two suites can't
 * share a file (Vitest vs Jest, DOM FormData vs React Native's).
 *
 * A document password in a URL is captured by server access logs, proxy logs and `Referer`
 * headers -- all places it would sit in plain text long after the import finished. It has to stay
 * in the request body, and "someone simplifies this to a query param" is exactly the kind of
 * change that looks harmless in review, which is why it's pinned here.
 */
describe('importApi.stagePdf — where the password goes', () => {
  const file = { uri: 'file:///statement.pdf', name: 'statement.pdf', type: 'application/pdf' };

  beforeEach(() => {
    post.mockReset().mockResolvedValue({ data: {} });
  });

  function lastCall() {
    const [url, body] = post.mock.calls[0];
    return { url: url as string, form: body as FormData };
  }

  it('puts the password in the form body, never in the URL', async () => {
    await importApi.stagePdf(file, undefined, 'AAAA1234');

    const { url, form } = lastCall();
    expect(url).toBe('/import/pdf/stage');
    expect(url).not.toContain('AAAA1234');
    expect(url).not.toContain('?');
    expect(form.get('password')).toBe('AAAA1234');
  });

  it('omits the field entirely when no password was given', async () => {
    await importApi.stagePdf(file);

    // An unprotected upload sends exactly the body it always did.
    expect(lastCall().form.get('password')).toBeNull();
  });

  it('omits an empty-string password rather than sending a blank field', async () => {
    await importApi.stagePdf(file, undefined, '');

    expect(lastCall().form.get('password')).toBeNull();
  });
});
