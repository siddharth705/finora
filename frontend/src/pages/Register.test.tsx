import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import Register from './Register';
import { useAuth } from '../context/AuthContext';

vi.mock('../context/AuthContext', () => ({
  useAuth: vi.fn(),
}));

const registerMock = vi.fn();

function renderRegister() {
  vi.mocked(useAuth).mockReturnValue({
    token: null,
    email: null,
    fullName: null,
    phoneVerified: false,
    login: vi.fn(),
    register: registerMock,
    setPhoneVerified: vi.fn(),
    logout: vi.fn(),
  });
  return render(
    <MemoryRouter>
      <Register />
    </MemoryRouter>
  );
}

async function fillValidFormExceptPhone(user: ReturnType<typeof userEvent.setup>, container: HTMLElement) {
  await user.type(screen.getByPlaceholderText('Enter your full name'), 'Jane Doe');
  await user.type(screen.getByPlaceholderText('you@example.com'), 'jane@example.com');
  await user.type(screen.getByPlaceholderText('XXXXXXXXXX'), '9876543210');
  // PasswordInput's <label> has no htmlFor/id linking it to the input it labels (a pre-existing
  // gap, not something this change introduces) -- getByLabelText can't resolve it, so target the
  // two password-type inputs directly instead, in their known DOM order (password, then confirm).
  const passwordInputs = container.querySelectorAll<HTMLInputElement>('input[type="password"]');
  await user.type(passwordInputs[0], 'Str0ng!Pass');
  await user.type(passwordInputs[1], 'Str0ng!Pass');
  await user.click(screen.getByRole('checkbox'));
}

describe('Register — mobile number field', () => {
  beforeEach(() => {
    registerMock.mockReset().mockResolvedValue({ phoneVerified: false, devOtp: '123456' });
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

  it('strips a leading "91" country code when a full number is pasted', async () => {
    renderRegister();
    const phoneInput = screen.getByPlaceholderText('XXXXXXXXXX') as HTMLInputElement;

    const pasteEvent = new Event('paste', { bubbles: true, cancelable: true }) as any;
    pasteEvent.clipboardData = { getData: () => '+91 98765 43210' };
    phoneInput.dispatchEvent(pasteEvent);

    expect(phoneInput.value).toBe('9876543210');
  });

  it('does NOT strip "91" from a genuine 10-digit number that happens to start with it', async () => {
    renderRegister();
    const phoneInput = screen.getByPlaceholderText('XXXXXXXXXX') as HTMLInputElement;

    const pasteEvent = new Event('paste', { bubbles: true, cancelable: true }) as any;
    pasteEvent.clipboardData = { getData: () => '9198765432' }; // exactly 10 digits already
    phoneInput.dispatchEvent(pasteEvent);

    expect(phoneInput.value).toBe('9198765432');
  });

  it('always shows the fixed 🇮🇳 +91 prefix next to the field', () => {
    renderRegister();
    expect(screen.getByText('+91')).toBeInTheDocument();
    expect(screen.getByText('🇮🇳')).toBeInTheDocument();
  });

  it('submits the phone number with +91 prepended, not stored with it', async () => {
    const user = userEvent.setup();
    const { container } = renderRegister();

    await fillValidFormExceptPhone(user, container);
    await user.click(screen.getByRole('button', { name: /Create account/i }));

    expect(registerMock).toHaveBeenCalledWith(
      'jane@example.com', 'Str0ng!Pass', 'Jane Doe', '+919876543210'
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

describe('Register — logo', () => {
  it('links back to the landing page', () => {
    renderRegister();
    const logoLinks = screen.getAllByRole('link', { name: /FINORA/i });
    expect(logoLinks.length).toBeGreaterThan(0);
    logoLinks.forEach((link) => expect(link).toHaveAttribute('href', '/'));
  });
});
