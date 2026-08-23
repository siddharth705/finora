import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Register from './Register';
import { useAuth } from '../context/AuthContext';

vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
}));

const registerMock = vi.fn();

function renderRegister(initialEntries: string[] = ['/register'], state?: { email?: string; phoneNumber?: string }) {
  vi.mocked(useAuth).mockReturnValue({
    token: null,
    bootstrapping: false,
    email: null,
    fullName: null,
    phoneVerified: false,
    login: vi.fn(),
    reactivate: vi.fn(),
    loginWithGoogle: vi.fn(),
    register: registerMock,
    setPhoneVerified: vi.fn(),
    logout: vi.fn(),
  });
  return render(
    <MemoryRouter initialEntries={state ? [{ pathname: initialEntries[0], state }] : initialEntries}>
      <Register />
    </MemoryRouter>
  );
}

async function fillValidFormExceptPhone(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByPlaceholderText('Enter your full name'), 'Jane Doe');
  await user.type(screen.getByPlaceholderText('you@example.com'), 'jane@example.com');
  await user.type(screen.getByPlaceholderText('XXXXXXXXXX'), '9876543210');
  // Bug fix regression: PasswordInput now accepts an id prop wired to a matching label htmlFor,
  // so these resolve via getByLabelText instead of the previous querySelectorAll workaround.
  await user.type(screen.getByLabelText('Password (min 8 characters)'), 'Str0ng!Pass');
  await user.type(screen.getByLabelText('Confirm password'), 'Str0ng!Pass');
  await user.click(screen.getByRole('checkbox'));
}

describe('Register — mobile number field', () => {
  beforeEach(() => {
    registerMock.mockReset().mockResolvedValue({ phoneVerified: false });
  });

  it('strips non-digit characters as they are typed', async () => {
    const user = userEvent.setup();
    renderRegister();

    const phoneInput = screen.getByPlaceholderText('XXXXXXXXXX') as HTMLInputElement;
    await user.type(phoneInput, 'abc987-6543!210');

    expect(phoneInput.value).toBe('9876543210');
  });

  it('caps typed input at 10 digits', async () => {
    const user = userEvent.setup();
    renderRegister();

    const phoneInput = screen.getByPlaceholderText('XXXXXXXXXX') as HTMLInputElement;
    await user.type(phoneInput, '98765432109999');

    expect(phoneInput.value).toBe('9876543210');
  });

  it('strips a leading "91" country code when a full number is pasted', () => {
    renderRegister();
    const phoneInput = screen.getByPlaceholderText('XXXXXXXXXX') as HTMLInputElement;

    fireEvent.paste(phoneInput, { clipboardData: { getData: () => '+91 98765 43210' } });

    expect(phoneInput.value).toBe('9876543210');
  });

  it('does NOT strip "91" from a genuine 10-digit number that happens to start with it', () => {
    renderRegister();
    const phoneInput = screen.getByPlaceholderText('XXXXXXXXXX') as HTMLInputElement;

    // exactly 10 digits already -- must survive untouched, not have "91" mistaken for a
    // country-code prefix and stripped down to 8 digits
    fireEvent.paste(phoneInput, { clipboardData: { getData: () => '9198765432' } });

    expect(phoneInput.value).toBe('9198765432');
  });

  it('always shows the fixed 🇮🇳 +91 prefix next to the field', () => {
    renderRegister();
    expect(screen.getByText('+91')).toBeInTheDocument();
    expect(screen.getByText('🇮🇳')).toBeInTheDocument();
  });

  it('submits the phone number with +91 prepended, not stored with it', async () => {
    const user = userEvent.setup();
    renderRegister();

    await fillValidFormExceptPhone(user);
    await user.click(screen.getByRole('button', { name: /Create account/i }));

    expect(registerMock).toHaveBeenCalledWith(
      'jane@example.com', 'Str0ng!Pass', 'Jane Doe', '+919876543210' /* synthetic-ok */, undefined
    );
  });

  // D-28 PR4-C: a referral link's `?ref=` param is the only way this page ever learns a referral
  // code -- there's no visible form field for it, by design (see Referrals.tsx's own doc comment).
  it('passes a referral code from the URL through to register()', async () => {
    const user = userEvent.setup();
    renderRegister(['/register?ref=ABCD1234']);

    await fillValidFormExceptPhone(user);
    await user.click(screen.getByRole('button', { name: /Create account/i }));

    expect(registerMock).toHaveBeenCalledWith(
      'jane@example.com', 'Str0ng!Pass', 'Jane Doe', '+919876543210' /* synthetic-ok */, 'ABCD1234'
    );
  });

  it('rejects a 10-digit number starting 0-5 on blur', async () => {
    const user = userEvent.setup();
    renderRegister();

    const phoneInput = screen.getByPlaceholderText('XXXXXXXXXX');
    await user.type(phoneInput, '1234567890');
    await user.tab(); // blur

    expect(await screen.findByText(/Enter a valid 10-digit mobile number/i)).toBeInTheDocument();
  });
});

// Phase 3 (§2.2): AuthEntry.tsx sends whichever field the identifier looked like once it
// learns nextAction is CONTINUE (no existing account) -- prefilled here so the user doesn't
// have to retype what they already entered on the entry page.
describe('Register — prefill from AuthEntry', () => {
  it('prefills the email field when arriving with an email in router state', () => {
    renderRegister(['/register'], { email: 'jane@example.com' });

    expect(screen.getByPlaceholderText('you@example.com')).toHaveValue('jane@example.com');
  });

  it('prefills the mobile number field, stripped to its local 10 digits, when arriving with a phone number in router state', () => {
    renderRegister(['/register'], { phoneNumber: '+919876543210' /* synthetic-ok: same fake number used elsewhere in this file */ });

    expect(screen.getByPlaceholderText('XXXXXXXXXX')).toHaveValue('9876543210' /* synthetic-ok: same fake number used elsewhere in this file */);
  });

  it('leaves both fields empty on an ordinary direct visit with no router state', () => {
    renderRegister();

    expect(screen.getByPlaceholderText('you@example.com')).toHaveValue('');
    expect(screen.getByPlaceholderText('XXXXXXXXXX')).toHaveValue('');
  });
});

describe('Register — duplicate account (409)', () => {
  beforeEach(() => {
    registerMock.mockReset();
  });

  it('offers a Continue to login link when the email or phone already belongs to an account', async () => {
    const user = userEvent.setup();
    registerMock.mockRejectedValue({
      response: { status: 409, data: { message: 'An account with this email already exists.' } },
    });
    renderRegister();

    await fillValidFormExceptPhone(user);
    await user.click(screen.getByRole('button', { name: /Create account/i }));

    expect(await screen.findByText(/already exists/i)).toBeInTheDocument();
    const continueLink = screen.getByRole('link', { name: /continue to login/i });
    expect(continueLink).toHaveAttribute('href', '/login');
  });

  it('does not offer Continue to login for an unrelated registration failure', async () => {
    const user = userEvent.setup();
    registerMock.mockRejectedValue({
      response: { status: 500, data: { message: 'Something went wrong.' } },
    });
    renderRegister();

    await fillValidFormExceptPhone(user);
    await user.click(screen.getByRole('button', { name: /Create account/i }));

    expect(await screen.findByText(/something went wrong/i)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /continue to login/i })).not.toBeInTheDocument();
  });
});

describe('Register — logo', () => {
  it('links back to the landing page', () => {
    renderRegister();
    const logoLinks = screen.getAllByRole('link', { name: /FINORA/i });
    expect(logoLinks.length).toBeGreaterThan(0);
    logoLinks.forEach((link) => expect(link).toHaveAttribute('href', '/'));
  });
});

describe('Register — Terms/Privacy links', () => {
  // Bug fix regression: these three links open in a new tab (target="_blank") but had no rel
  // attribute at all, leaving the new tab holding a `window.opener` handle back to this
  // in-progress registration form -- the classic reverse-tabnabbing shape. eslint.config.js's
  // `no-restricted-syntax` rule now catches this pattern at lint time for any future
  // target="_blank" link app-wide; this test guards the specific regression on this page too.
  it('every target="_blank" link carries rel="noopener noreferrer"', () => {
    renderRegister();
    const blankLinks = screen.getAllByRole('link').filter((link) => link.getAttribute('target') === '_blank');

    expect(blankLinks.length).toBeGreaterThan(0); // otherwise this test would pass vacuously
    blankLinks.forEach((link) => {
      expect(link).toHaveAttribute('rel', expect.stringContaining('noopener'));
    });
  });
});
