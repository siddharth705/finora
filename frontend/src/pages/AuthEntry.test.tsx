import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation } from 'react-router-dom';
import AuthEntry from './AuthEntry';
import { AuthProvider } from '../context/AuthContext';
import { authApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  authApi: { identify: vi.fn(), loginWithGoogle: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function LoginStub() {
  const location = useLocation();
  const state = location.state as { identifier?: string } | null;
  return (
    <div>
      <p>Login page</p>
      <p>identifier={state?.identifier ?? 'none'}</p>
    </div>
  );
}

function RegisterStub() {
  const location = useLocation();
  const state = location.state as { email?: string; phoneNumber?: string } | null;
  return (
    <div>
      <p>Register page</p>
      <p>email={state?.email ?? 'none'}</p>
      <p>phoneNumber={state?.phoneNumber ?? 'none'}</p>
    </div>
  );
}

function renderAuthEntry() {
  return render(
    <MemoryRouter initialEntries={['/auth']}>
      <AuthProvider>
        <Routes>
          <Route path="/auth" element={<AuthEntry />} />
          <Route path="/login" element={<LoginStub />} />
          <Route path="/register" element={<RegisterStub />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('AuthEntry', () => {
  beforeEach(() => {
    vi.mocked(authApi.identify).mockReset();
  });

  it('shows a validation error and makes no API call when submitted empty', async () => {
    renderAuthEntry();
    const user = userEvent.setup();

    await user.click(screen.getByRole('button', { name: /continue/i }));

    expect(screen.getByText('Enter your email or mobile number.')).toBeInTheDocument();
    expect(authApi.identify).not.toHaveBeenCalled();
  });

  it('routes to /login with the identifier prefilled when nextAction is EXISTS', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    renderAuthEntry();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/email or mobile number/i), 'jane@example.com');
    await user.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(screen.getByText('Login page')).toBeInTheDocument());
    expect(authApi.identify).toHaveBeenCalledWith('jane@example.com');
    expect(screen.getByText('identifier=jane@example.com')).toBeInTheDocument();
  });

  it('routes to /register with the email prefilled when nextAction is CONTINUE and the identifier looks like an email', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    renderAuthEntry();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/email or mobile number/i), 'newuser@example.com');
    await user.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(screen.getByText('Register page')).toBeInTheDocument());
    expect(screen.getByText('email=newuser@example.com')).toBeInTheDocument();
    expect(screen.getByText('phoneNumber=none')).toBeInTheDocument();
  });

  it('routes to /register with the phone number prefilled when nextAction is CONTINUE and the identifier looks like a phone number', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    renderAuthEntry();
    const user = userEvent.setup();

    const fakePhone = '+919876543210'; // synthetic-ok: same fake sequential number used throughout Register.test.tsx
    await user.type(screen.getByLabelText(/email or mobile number/i), fakePhone);
    await user.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(screen.getByText('Register page')).toBeInTheDocument());
    expect(screen.getByText(`phoneNumber=${fakePhone}`)).toBeInTheDocument();
    expect(screen.getByText('email=none')).toBeInTheDocument();
  });

  it('shows a server error message and stays on the page when identify() fails', async () => {
    vi.mocked(authApi.identify).mockRejectedValue({ response: { data: { message: 'Too many attempts. Try again later.' } } });
    renderAuthEntry();
    const user = userEvent.setup();

    await user.type(screen.getByLabelText(/email or mobile number/i), 'jane@example.com');
    await user.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(screen.getByText('Too many attempts. Try again later.')).toBeInTheDocument());
    expect(screen.queryByText('Login page')).not.toBeInTheDocument();
  });
});
