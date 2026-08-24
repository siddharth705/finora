import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { RegisterStep } from './RegisterStep';
import { AuthProvider } from '../../context/AuthContext';
import { authApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  authApi: { register: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function renderStep(props: Partial<Parameters<typeof RegisterStep>[0]> = {}) {
  const onSuccess = vi.fn();
  const onAccountExists = vi.fn();
  render(
    <MemoryRouter>
      <AuthProvider>
        <RegisterStep prefill={{}} referralCode={undefined} onSuccess={onSuccess} onAccountExists={onAccountExists} {...props} />
      </AuthProvider>
    </MemoryRouter>
  );
  return { onSuccess, onAccountExists };
}

async function fillValidForm() {
  await userEvent.type(screen.getByLabelText('Full name'), 'Jane Doe');
  await userEvent.type(screen.getByLabelText('Email'), 'jane@example.com');
  await userEvent.type(screen.getByLabelText('Mobile number'), '9876500011'); // synthetic-ok: fake sequential example number
  await userEvent.type(screen.getByLabelText('Password (min 8 characters)'), 'correct-password-1');
  await userEvent.type(screen.getByLabelText('Confirm password'), 'correct-password-1');
  await userEvent.click(screen.getByRole('checkbox'));
}

describe('RegisterStep', () => {
  it('prefills the email field from the prefill prop', () => {
    renderStep({ prefill: { email: 'new@example.com' } });
    expect(screen.getByLabelText('Email')).toHaveValue('new@example.com');
  });

  it('calls onSuccess with phoneVerified on successful registration', async () => {
    vi.mocked(authApi.register).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane Doe', phoneVerified: false },
    } as any);
    const { onSuccess } = renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(false));
  });

  it('calls onAccountExists with the identifier on a 409 instead of navigating away', async () => {
    vi.mocked(authApi.register).mockImplementation(async () => {
      throw Object.assign(new Error('Account already exists.'), {
        response: { status: 409, data: { message: 'Account already exists.' } },
      });
    });
    const { onAccountExists, onSuccess } = renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(onAccountExists).toHaveBeenCalledWith('jane@example.com'));
    expect(onSuccess).not.toHaveBeenCalled();
    expect(screen.queryByRole('link', { name: /continue to login/i })).not.toBeInTheDocument();
  });

  it('does not call onSuccess or onAccountExists when register() fails for a non-409 reason', async () => {
    vi.mocked(authApi.register).mockImplementation(async () => {
      throw Object.assign(new Error('Bad input.'), {
        response: { status: 400, data: { message: 'Bad input.' } },
      });
    });
    const { onAccountExists, onSuccess } = renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(screen.getByText('Bad input.')).toBeInTheDocument());
    expect(onAccountExists).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });
});
