import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { GoogleSignInButton } from './GoogleSignInButton';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';

/**
 * Mocks lib/googleIdentity.ts wholesale rather than simulating a real <script> load -- that
 * module's own script-injection/caching/failure mechanics already have dedicated coverage in
 * googleIdentity.test.ts (including the module-scope caching that makes DOM-event simulation
 * order-sensitive across tests). This file's job is narrower: given whatever
 * loadGoogleIdentityServices resolves or rejects with, does the component wire it up correctly.
 */
vi.mock('../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));

// jsdom implements neither ResizeObserver nor real layout, so a real one would never fire and
// getBoundingClientRect() would always read 0. This stub records the callback per observed
// element and lets each test fire it with whatever contentRect.width it wants to simulate --
// closest thing to controlling "what the browser measured" without a real layout engine.
let resizeCallbacks: Map<Element, ResizeObserverCallback>;
function fireResize(el: Element, width: number) {
  resizeCallbacks.get(el)?.([{ contentRect: { width } } as ResizeObserverEntry], {} as ResizeObserver);
}

beforeEach(() => {
  vi.mocked(isGoogleLoginConfigured).mockReset();
  vi.mocked(loadGoogleIdentityServices).mockReset();
  resizeCallbacks = new Map();
  vi.stubGlobal('ResizeObserver', class {
    constructor(private callback: ResizeObserverCallback) {}
    observe(el: Element) { resizeCallbacks.set(el, this.callback); }
    unobserve(el: Element) { resizeCallbacks.delete(el); }
    disconnect() { resizeCallbacks.clear(); }
  });
});

afterEach(() => {
  vi.unstubAllEnvs();
});

describe('GoogleSignInButton', () => {
  it('renders nothing and never loads the script when unconfigured', () => {
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(false);

    const { container } = render(
      <GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} />
    );

    expect(container).toBeEmptyDOMElement();
    expect(loadGoogleIdentityServices).not.toHaveBeenCalled();
  });

  it('initializes GIS with the configured client id and renders the button at the observed width', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    const renderButton = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);

    const { container } = render(<GoogleSignInButton text="signup_with" onCredential={vi.fn()} onError={vi.fn()} />);

    await waitFor(() => expect(initialize).toHaveBeenCalledWith(
      expect.objectContaining({ client_id: 'test-client-id.apps.googleusercontent.com' })
    ));

    // Real ResizeObserver fires once as soon as observe() is called, reporting whatever width the
    // browser actually settled on -- this simulates that first callback.
    fireResize(container.querySelector('[aria-busy]')!, 288);

    expect(renderButton).toHaveBeenCalledWith(
      expect.any(HTMLElement),
      expect.objectContaining({ text: 'signup_with', theme: 'outline', width: '288', logo_alignment: 'center' }),
    );
  });

  it('re-renders at the new width when the container is resized', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    const renderButton = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);

    const { container } = render(<GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} />);
    await waitFor(() => expect(initialize).toHaveBeenCalled());
    const target = container.querySelector('[aria-busy]')!;

    // Simulates exactly the production bug this fix closes: an early, too-narrow measurement
    // (the container hadn't finished laying out yet) followed by the real, settled width.
    fireResize(target, 107);
    fireResize(target, 304);

    expect(renderButton).toHaveBeenLastCalledWith(expect.any(HTMLElement), expect.objectContaining({ width: '304' }));
  });

  it('caps the rendered width at 400px, matching GIS documented max', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    const renderButton = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);

    const { container } = render(<GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} />);
    await waitFor(() => expect(initialize).toHaveBeenCalled());

    fireResize(container.querySelector('[aria-busy]')!, 900);

    expect(renderButton).toHaveBeenCalledWith(expect.any(HTMLElement), expect.objectContaining({ width: '400' }));
  });

  it('hands the credential straight to onCredential when Google calls back', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const onCredential = vi.fn();
    const initialize = vi.fn();
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton: vi.fn() } as any);

    render(<GoogleSignInButton text="signin_with" onCredential={onCredential} onError={vi.fn()} />);
    await waitFor(() => expect(initialize).toHaveBeenCalled());

    // Simulate Google's own button invoking the callback it was configured with.
    const { callback } = initialize.mock.calls[0][0];
    callback({ credential: 'a-real-looking-jwt' });

    expect(onCredential).toHaveBeenCalledWith('a-real-looking-jwt');
  });

  it('reports the iframe\'s own real rendered width via onRenderedWidth, not the requested width', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    const initialize = vi.fn();
    // Real GIS inserts an iframe into the container renderButton() is called with; the requested
    // `width` param and the iframe's own eventual rendered width are two different numbers (see
    // the component's own comment) -- this mock creates the iframe so the test can drive that gap.
    const renderButton = vi.fn((container: HTMLElement) => {
      container.appendChild(document.createElement('iframe'));
    });
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);
    const onRenderedWidth = vi.fn();

    const { container } = render(
      <GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={vi.fn()} onRenderedWidth={onRenderedWidth} />
    );
    await waitFor(() => expect(initialize).toHaveBeenCalled());

    fireResize(container.querySelector('[aria-busy]')!, 400);
    const iframe = container.querySelector('iframe')!;
    fireResize(iframe, 420);

    expect(onRenderedWidth).toHaveBeenCalledWith(420);
  });

  it('reports onError when Google Identity Services fails to load', async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    vi.mocked(loadGoogleIdentityServices).mockRejectedValue(new Error('Failed to load Google Identity Services.'));
    const onError = vi.fn();

    render(<GoogleSignInButton text="signin_with" onCredential={vi.fn()} onError={onError} />);

    await waitFor(() => expect(onError).toHaveBeenCalledWith(
      'Sign in with Google is unavailable right now. Please try again later.'
    ));
  });
});
