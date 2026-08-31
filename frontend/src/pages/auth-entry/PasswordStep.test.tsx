import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { PasswordStep } from './PasswordStep';
import { AuthProvider } from '../../context/AuthContext';
import { authApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  authApi: { login: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function renderStep(props: Partial<Parameters<typeof PasswordStep>[0]> = {}) {
  const onSuccess = vi.fn();
  const onNotYou = vi.fn();
  render(
    <MemoryRouter>
      <AuthProvider>
        <PasswordStep identifier="jane@example.com" banner={null} onSuccess={onSuccess} onNotYou={onNotYou} {...props} />
      </AuthProvider>
    </MemoryRouter>
  );
  return { onSuccess, onNotYou };
}

describe('PasswordStep', () => {
  it('prefills the identifier field from props', () => {
    renderStep();
    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('jane@example.com');
  });

  it('shows the banner prop when present', () => {
    renderStep({ banner: 'Password updated successfully. Please sign in using your new password.' });
    expect(screen.getByText('Password updated successfully. Please sign in using your new password.')).toBeInTheDocument();
  });

  it('calls onSuccess with phoneVerified on a successful login', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    } as any);
    const { onSuccess } = renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(true));
  });

  it('does NOT call onSuccess when login() rejects -- forcing the password step to render is not authentication', async () => {
    vi.mocked(authApi.login).mockImplementation(async () => {
      throw Object.assign(new Error('Invalid credentials.'), {
        response: { data: { message: 'Invalid credentials.' } },
      });
    });
    const { onSuccess } = renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('Invalid credentials.')).toBeInTheDocument());
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('calls onNotYou when "Not you?" is clicked', async () => {
    const { onNotYou } = renderStep();
    await userEvent.click(screen.getByRole('button', { name: /not you/i }));
    expect(onNotYou).toHaveBeenCalled();
  });
});
