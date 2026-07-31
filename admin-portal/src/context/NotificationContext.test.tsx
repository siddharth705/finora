import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, renderHook, act } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { NotificationProvider, useNotify } from './NotificationContext';

function Trigger({ type, message }: { type: 'success' | 'error'; message: string }) {
  const notify = useNotify();
  return (
    <button type="button" onClick={() => notify[type](message)}>
      Fire {type}
    </button>
  );
}

describe('NotificationContext', () => {
  it('throws when useNotify is called outside a NotificationProvider', () => {
    // Swallow the expected React error-boundary console noise for this one assertion.
    const spy = vi.spyOn(console, 'error').mockImplementation(() => {});
    expect(() => renderHook(() => useNotify())).toThrow('useNotify must be used within NotificationProvider');
    spy.mockRestore();
  });

  it('shows a success toast when notify.success is called', async () => {
    const user = userEvent.setup();
    render(
      <NotificationProvider>
        <Trigger type="success" message="User suspended." />
      </NotificationProvider>
    );

    await user.click(screen.getByRole('button', { name: 'Fire success' }));

    expect(screen.getByText('User suspended.')).toBeInTheDocument();
    expect(screen.getByRole('status')).toBeInTheDocument();
  });

  it('shows an error toast when notify.error is called', async () => {
    const user = userEvent.setup();
    render(
      <NotificationProvider>
        <Trigger type="error" message="Failed to save." />
      </NotificationProvider>
    );

    await user.click(screen.getByRole('button', { name: 'Fire error' }));

    expect(screen.getByText('Failed to save.')).toBeInTheDocument();
  });

  it('stacks multiple toasts at once', async () => {
    const user = userEvent.setup();
    render(
      <NotificationProvider>
        <Trigger type="success" message="First." />
        <Trigger type="error" message="Second." />
      </NotificationProvider>
    );

    await user.click(screen.getByRole('button', { name: 'Fire success' }));
    await user.click(screen.getByRole('button', { name: 'Fire error' }));

    expect(screen.getByText('First.')).toBeInTheDocument();
    expect(screen.getByText('Second.')).toBeInTheDocument();
  });

  it('dismisses a toast early when its close button is clicked', async () => {
    const user = userEvent.setup();
    render(
      <NotificationProvider>
        <Trigger type="success" message="Dismiss me." />
      </NotificationProvider>
    );

    await user.click(screen.getByRole('button', { name: 'Fire success' }));
    expect(screen.getByText('Dismiss me.')).toBeInTheDocument();

    await user.click(screen.getByLabelText('Dismiss notification'));

    expect(screen.queryByText('Dismiss me.')).not.toBeInTheDocument();
  });

  describe('auto-dismiss timing', () => {
    beforeEach(() => {
      vi.useFakeTimers({ shouldAdvanceTime: true });
    });
    afterEach(() => {
      vi.useRealTimers();
    });

    it('auto-dismisses a success toast after 4 seconds', async () => {
      const user = userEvent.setup({ delay: null });
      render(
        <NotificationProvider>
          <Trigger type="success" message="Auto success." />
        </NotificationProvider>
      );

      await user.click(screen.getByRole('button', { name: 'Fire success' }));
      expect(screen.getByText('Auto success.')).toBeInTheDocument();

      await act(async () => {
        vi.advanceTimersByTime(4000);
      });

      expect(screen.queryByText('Auto success.')).not.toBeInTheDocument();
    });

    it('keeps an error toast alive past 4 seconds but auto-dismisses by 6', async () => {
      const user = userEvent.setup({ delay: null });
      render(
        <NotificationProvider>
          <Trigger type="error" message="Auto error." />
        </NotificationProvider>
      );

      await user.click(screen.getByRole('button', { name: 'Fire error' }));

      await act(async () => {
        vi.advanceTimersByTime(4000);
      });
      expect(screen.getByText('Auto error.')).toBeInTheDocument();

      await act(async () => {
        vi.advanceTimersByTime(2000);
      });
      expect(screen.queryByText('Auto error.')).not.toBeInTheDocument();
    });
  });
});
