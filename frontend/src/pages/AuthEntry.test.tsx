import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useNavigate } from 'react-router-dom';
import { useEffect } from 'react';
import AuthEntry from './AuthEntry';
import { AuthProvider } from '../context/AuthContext';
import { authApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  authApi: { identify: vi.fn(), login: vi.fn(), register: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function AppStub() {
  return <div>App home</div>;
}

// Lets a test navigate to /auth WITH router state, the same way ResetPassword.tsx's deep link does.
function NavigateWithState({ state }: { state: unknown }) {
  const navigate = useNavigate();
  useEffect(() => { void navigate('/auth', { state, replace: true }); }, [navigate, state]);
  return null;
}

function renderAt(initialState: unknown = undefined) {
  render(
    <MemoryRouter initialEntries={initialState === undefined ? ['/auth'] : ['/start']}>
      <AuthProvider>
        <Routes>
          <Route path="/auth" element={<AuthEntry />} />
          <Route path="/app" element={<AppStub />} />
          <Route path="/verify-phone" element={<div>Verify phone</div>} />
          <Route path="/start" element={<NavigateWithState state={initialState} />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('AuthEntry orchestrator', () => {
  it('starts on the identify step by default', () => {
    renderAt();
    expect(screen.getByLabelText('Email or mobile number')).toBeInTheDocument();
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
  });

  it('identify -> EXISTS -> password -> successful login -> /app, with no page navigation', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    vi.mocked(authApi.login).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    } as any);
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    const passwordField = await screen.findByLabelText('Password');
    await userEvent.type(passwordField, 'correct-password-1');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('App home')).toBeInTheDocument());
  });

  it('identify -> CONTINUE -> register -> successful registration -> /verify-phone', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    vi.mocked(authApi.register).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'new@example.com', fullName: 'Jane Doe', phoneVerified: false },
    } as any);
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'new@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    expect(await screen.findByLabelText('Email')).toHaveValue('new@example.com');
    await userEvent.type(screen.getByLabelText('Full name'), 'Jane Doe');
    await userEvent.type(screen.getByLabelText('Mobile number'), '9876500011'); // synthetic-ok: fake sequential example number
    await userEvent.type(screen.getByLabelText('Password (min 8 characters)'), 'correct-password-1');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(screen.getByText('Verify phone')).toBeInTheDocument());
  });

  it('register 409 switches to the password step with the identifier and a banner, not a page navigation', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    vi.mocked(authApi.register).mockImplementation(async () => {
      throw Object.assign(new Error('Account already exists.'), {
        response: { status: 409, data: { message: 'Account already exists.' } },
      });
    });
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'taken@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    await userEvent.type(await screen.findByLabelText('Full name'), 'Jane Doe');
    await userEvent.type(screen.getByLabelText('Mobile number'), '9876500011'); // synthetic-ok: fake sequential example number
    await userEvent.type(screen.getByLabelText('Password (min 8 characters)'), 'correct-password-1');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    const passwordField = await screen.findByLabelText('Password');
    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('taken@example.com');
    expect(passwordField).toBeInTheDocument();
  });

  it('deep-link entry with skipToPassword starts directly on the password step, prefilled with a banner', async () => {
    renderAt({
      identifier: 'jane@example.com',
      banner: 'Password reset successfully. Please sign in using your new password.',
      skipToPassword: true,
    });

    expect(await screen.findByLabelText('Email or mobile number')).toHaveValue('jane@example.com');
    expect(screen.queryByRole('button', { name: /^continue$/i })).not.toBeInTheDocument();
    expect(screen.getByText('Password reset successfully. Please sign in using your new password.')).toBeInTheDocument();
  });

  it('"Not you?" returns to the identify step with a blank identifier and clears the password field', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    await userEvent.type(await screen.findByLabelText('Password'), 'some-password');
    await userEvent.click(screen.getByRole('button', { name: /not you/i }));

    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('');
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();

    // Going through identify again proves the password field is genuinely gone, not just hidden.
    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    expect(await screen.findByLabelText('Password')).toHaveValue('');
  });

  it('a bfcache restore (pageshow with persisted=true) resets to identify even mid-flow', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    await screen.findByLabelText('Password');

    const pageShowEvent = new Event('pageshow');
    Object.defineProperty(pageShowEvent, 'persisted', { value: true });
    fireEvent(window, pageShowEvent);

    await waitFor(() => expect(screen.queryByLabelText('Password')).not.toBeInTheDocument());
    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('');
  });

  it('regression: rendering the password step alone never authenticates -- onSuccess only fires after a real login() resolution', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    vi.mocked(authApi.login).mockImplementation(async () => {
      throw Object.assign(new Error('Invalid credentials.'), {
        response: { data: { message: 'Invalid credentials.' } },
      });
    });
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    await userEvent.type(await screen.findByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('Invalid credentials.')).toBeInTheDocument());
    expect(screen.queryByText('App home')).not.toBeInTheDocument();
  });
});
