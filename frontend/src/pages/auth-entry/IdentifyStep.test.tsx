import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { IdentifyStep } from './IdentifyStep';
import { authApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  authApi: { identify: vi.fn() },
}));

// No global beforeEach reset/clear here -- every test sets its own mock behavior explicitly, and
// a shared mockReset()/mockClear() running before a test whose mock throws asynchronously trips
// vitest 4's unhandled-rejection tracking (observed directly: removing it is what fixes the last
// test below). The one test that needs a clean call-count slate (checking
// authApi.identify was never called) clears it locally instead.

describe('IdentifyStep', () => {
  it('calls onExists with the trimmed identifier when nextAction is EXISTS', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    const onExists = vi.fn();
    render(<IdentifyStep onExists={onExists} onContinue={vi.fn()} />);

    await userEvent.type(screen.getByLabelText('Email or mobile number'), '  jane@example.com  ');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(onExists).toHaveBeenCalledWith('jane@example.com'));
  });

  it('calls onContinue with an email prefill when nextAction is CONTINUE and the identifier looks like an email', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    const onContinue = vi.fn();
    render(<IdentifyStep onExists={vi.fn()} onContinue={onContinue} />);

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'new@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(onContinue).toHaveBeenCalledWith('new@example.com', { email: 'new@example.com' }));
  });

  it('calls onContinue with a phoneNumber prefill when the identifier looks like a phone number', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    const onContinue = vi.fn();
    render(<IdentifyStep onExists={vi.fn()} onContinue={onContinue} />);

    await userEvent.type(screen.getByLabelText('Email or mobile number'), '+919876500011'); // synthetic-ok: fake sequential example number
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(onContinue).toHaveBeenCalledWith('+919876500011', { phoneNumber: '+919876500011' })); // synthetic-ok
  });

  it('shows an error and calls neither callback when the identifier is blank', async () => {
    vi.mocked(authApi.identify).mockClear(); // clean call count for the assertion below
    const onExists = vi.fn();
    const onContinue = vi.fn();
    render(<IdentifyStep onExists={onExists} onContinue={onContinue} />);

    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    expect(await screen.findByText('Enter your email or mobile number.')).toBeInTheDocument();
    expect(onExists).not.toHaveBeenCalled();
    expect(onContinue).not.toHaveBeenCalled();
    expect(authApi.identify).not.toHaveBeenCalled();
  });

  it('shows the backend error message and does not call either callback when identify() rejects', async () => {
    // Deliberately not mockRejectedValue -- that constructs the rejected Promise eagerly at mock
    // setup time, which vitest 4's stricter unhandled-rejection detection can flag as an uncaught
    // error before the component ever calls and awaits it. An async throw only creates the
    // rejection when actually invoked.
    vi.mocked(authApi.identify).mockImplementation(async () => {
      throw Object.assign(new Error('Too many attempts, try again later.'), {
        response: { data: { message: 'Too many attempts, try again later.' } },
      });
    });
    const onExists = vi.fn();
    const onContinue = vi.fn();
    render(<IdentifyStep onExists={onExists} onContinue={onContinue} />);

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(screen.getByText('Too many attempts, try again later.')).toBeInTheDocument());
    expect(onExists).not.toHaveBeenCalled();
    expect(onContinue).not.toHaveBeenCalled();
  });
});
