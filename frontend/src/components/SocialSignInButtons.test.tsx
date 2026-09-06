import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, waitFor } from '@testing-library/react';
import { SocialSignInButtons } from './SocialSignInButtons';
import { isGoogleLoginConfigured, loadGoogleIdentityServices } from '../lib/googleIdentity';
import { isAppleLoginConfigured } from '../lib/appleIdentity';

// Same wholesale-mock approach as GoogleSignInButton.test.tsx/AppleSignInButton.test.tsx -- this
// file's job is narrower still: does the width Google reports actually reach the caller via
// onWidthKnown, not the two buttons' own internal wiring (already covered in their own tests).
vi.mock('../lib/googleIdentity', () => ({
  isGoogleLoginConfigured: vi.fn(),
  loadGoogleIdentityServices: vi.fn(),
}));
vi.mock('../lib/appleIdentity', () => ({
  isAppleLoginConfigured: vi.fn(),
  loadAppleIdServices: vi.fn(),
}));

let resizeCallbacks: Map<Element, ResizeObserverCallback>;
function fireResize(el: Element, width: number) {
  resizeCallbacks.get(el)?.([{ contentRect: { width } } as ResizeObserverEntry], {} as ResizeObserver);
}

beforeEach(() => {
  vi.mocked(isGoogleLoginConfigured).mockReset();
  vi.mocked(loadGoogleIdentityServices).mockReset();
  vi.mocked(isAppleLoginConfigured).mockReset();
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

describe('SocialSignInButtons', () => {
  it("forwards Google's real rendered width via onWidthKnown", async () => {
    vi.stubEnv('VITE_GOOGLE_LOGIN_CLIENT_ID', 'test-client-id.apps.googleusercontent.com');
    vi.mocked(isGoogleLoginConfigured).mockReturnValue(true);
    vi.mocked(isAppleLoginConfigured).mockReturnValue(false); // keep this test focused on the width sync, not Apple's own render path
    const initialize = vi.fn();
    const renderButton = vi.fn((container: HTMLElement) => {
      container.appendChild(document.createElement('iframe'));
    });
    vi.mocked(loadGoogleIdentityServices).mockResolvedValue({ initialize, renderButton } as any);
    const onWidthKnown = vi.fn();

    const { container } = render(
      <SocialSignInButtons
        googleText="signin_with"
        onGoogleCredential={vi.fn()}
        onAppleCredential={vi.fn()}
        onError={vi.fn()}
        onWidthKnown={onWidthKnown}
      />
    );
    await waitFor(() => expect(initialize).toHaveBeenCalled());

    fireResize(container.querySelector('[aria-busy]')!, 400);
    fireResize(container.querySelector('iframe')!, 420);

    await waitFor(() => expect(onWidthKnown).toHaveBeenCalledWith(420));
  });
});
