import { describe, it, expect, vi, afterEach } from 'vitest';

afterEach(() => {
  document.head.querySelectorAll('script').forEach((s) => s.remove());
  delete (window as any).Razorpay;
});

describe('loadRazorpayCheckout', () => {
  // Same reason googleIdentity.test.ts's own comment gives: the module caches its script-loading
  // promise at module scope on purpose (meant to survive every real page mount), so each test here
  // needs a fresh module instance or one test's cached promise leaks into the next.
  async function freshLoadRazorpayCheckout() {
    vi.resetModules();
    const mod = await import('./razorpayCheckout');
    return mod.loadRazorpayCheckout;
  }

  it('injects the Checkout script into <head> exactly once even across concurrent callers', async () => {
    const loadRazorpayCheckout = await freshLoadRazorpayCheckout();
    const promise1 = loadRazorpayCheckout();
    const promise2 = loadRazorpayCheckout();

    expect(document.querySelectorAll('script[src="https://checkout.razorpay.com/v1/checkout.js"]')).toHaveLength(1);

    const RazorpayCtor = vi.fn();
    (window as any).Razorpay = RazorpayCtor;
    document.querySelector('script[src="https://checkout.razorpay.com/v1/checkout.js"]')!.dispatchEvent(new Event('load'));

    const [ctor1, ctor2] = await Promise.all([promise1, promise2]);
    expect(ctor1).toBe(RazorpayCtor);
    expect(ctor2).toBe(ctor1);
  });

  it('resolves immediately, with no new script tag, once window.Razorpay is already present', async () => {
    const loadRazorpayCheckout = await freshLoadRazorpayCheckout();
    const RazorpayCtor = vi.fn();
    (window as any).Razorpay = RazorpayCtor;

    const resolved = await loadRazorpayCheckout();

    expect(resolved).toBe(RazorpayCtor);
    expect(document.querySelectorAll('script[src="https://checkout.razorpay.com/v1/checkout.js"]')).toHaveLength(0);
  });

  it('rejects rather than hanging forever when the script fails to load', async () => {
    const loadRazorpayCheckout = await freshLoadRazorpayCheckout();
    const promise = loadRazorpayCheckout();
    document.querySelector('script[src="https://checkout.razorpay.com/v1/checkout.js"]')!.dispatchEvent(new Event('error'));

    await expect(promise).rejects.toThrow('Failed to load Razorpay Checkout.');
  });
});

describe('openRazorpayCheckout', () => {
  async function freshOpenRazorpayCheckout() {
    vi.resetModules();
    const mod = await import('./razorpayCheckout');
    return mod.openRazorpayCheckout;
  }

  // Razorpay's Checkout constructor is already present (window.Razorpay) in each of these tests,
  // so loadRazorpayCheckout resolves immediately -- these tests are about the instance built from
  // it, not the script-loading path (covered above).
  it('resolves { paymentId } from the handler callback, and opens the widget', async () => {
    const openRazorpayCheckout = await freshOpenRazorpayCheckout();
    const open = vi.fn();
    const on = vi.fn();
    let capturedHandler: ((r: { razorpay_payment_id: string }) => void) | undefined;
    // A regular function expression, not an arrow function -- vi.fn()'s mock is invoked with
    // `new` by openRazorpayCheckout, and an arrow function can never be used as a constructor
    // ("X is not a constructor"), regardless of how vi.fn() wraps it.
    const RazorpayCtor = vi.fn().mockImplementation(function (options: any) {
      capturedHandler = options.handler;
      return { open, on };
    });
    (window as any).Razorpay = RazorpayCtor;

    const promise = openRazorpayCheckout({
      key: 'rzp_test', subscription_id: 'sub_123', name: 'Fynora', description: 'PLUS — MONTHLY',
    });
    // openRazorpayCheckout awaits loadRazorpayCheckout() before constructing the instance, even
    // when window.Razorpay is already present (Promise.resolve still needs a microtask to
    // settle) -- the constructor, and so capturedHandler, isn't set until after that tick.
    await Promise.resolve();
    capturedHandler!({ razorpay_payment_id: 'pay_123' });

    await expect(promise).resolves.toEqual({ paymentId: 'pay_123' });
    expect(open).toHaveBeenCalled();
    expect(RazorpayCtor).toHaveBeenCalledWith(expect.objectContaining({
      key: 'rzp_test', subscription_id: 'sub_123',
    }));
  });

  it('resolves null when the widget is dismissed', async () => {
    const openRazorpayCheckout = await freshOpenRazorpayCheckout();
    let capturedOnDismiss: (() => void) | undefined;
    const RazorpayCtor = vi.fn().mockImplementation(function (options: any) {
      capturedOnDismiss = options.modal.ondismiss;
      return { open: vi.fn(), on: vi.fn() };
    });
    (window as any).Razorpay = RazorpayCtor;

    const promise = openRazorpayCheckout({
      key: 'rzp_test', subscription_id: 'sub_123', name: 'Fynora', description: 'PLUS — MONTHLY',
    });
    await Promise.resolve();
    capturedOnDismiss!();

    await expect(promise).resolves.toBeNull();
  });

  it('resolves null when the payment fails', async () => {
    const openRazorpayCheckout = await freshOpenRazorpayCheckout();
    let capturedFailureHandler: (() => void) | undefined;
    const RazorpayCtor = vi.fn().mockImplementation(function () {
      return {
        open: vi.fn(),
        on: vi.fn((event: string, handler: () => void) => {
          if (event === 'payment.failed') capturedFailureHandler = handler;
        }),
      };
    });
    (window as any).Razorpay = RazorpayCtor;

    const promise = openRazorpayCheckout({
      key: 'rzp_test', subscription_id: 'sub_123', name: 'Fynora', description: 'PLUS — MONTHLY',
    });
    await Promise.resolve();
    capturedFailureHandler!();

    await expect(promise).resolves.toBeNull();
  });
});
