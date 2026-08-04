import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

import { ErrorBoundary } from './ErrorBoundary';

const reportHandledError = vi.fn();
vi.mock('../lib/monitoring', () => ({
  reportHandledError: (...args: unknown[]) => reportHandledError(...args),
}));

/**
 * The behaviour under test is "a thrown render error becomes a recovery panel instead of a blank
 * page". React logs caught errors to console.error regardless, and the boundary adds its own line,
 * so both are silenced here -- otherwise the suite output looks like a failure when it passes.
 */
let consoleError: ReturnType<typeof vi.spyOn>;
beforeEach(() => {
  reportHandledError.mockClear();
  consoleError = vi.spyOn(console, 'error').mockImplementation(() => {});
});
afterEach(() => {
  consoleError.mockRestore();
});

function Boom(): React.ReactElement {
  throw new Error('render blew up');
}

describe('ErrorBoundary', () => {
  it('renders its children untouched when nothing throws', () => {
    render(
      <ErrorBoundary context="test">
        <p>the real page</p>
      </ErrorBoundary>
    );

    expect(screen.getByText('the real page')).toBeInTheDocument();
  });

  it('shows a recovery panel instead of nothing when a child throws', () => {
    const { container } = render(
      <ErrorBoundary context="test">
        <Boom />
      </ErrorBoundary>
    );

    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByText(/didn't load correctly/i)).toBeInTheDocument();
    // The whole point: the previous behaviour rendered literally nothing.
    expect(container.textContent?.trim()).not.toBe('');
  });

  /** An admin console going blank mid-action reads as "did my change go through?", so the copy
   *  has to answer that. */
  it('tells the admin no changes were made', () => {
    render(
      <ErrorBoundary context="test">
        <Boom />
      </ErrorBoundary>
    );

    expect(screen.getByText(/no changes were made/i)).toBeInTheDocument();
  });

  it('reports the failure with its context so the broken area is identifiable', () => {
    render(
      <ErrorBoundary context="app-route">
        <Boom />
      </ErrorBoundary>
    );

    expect(reportHandledError).toHaveBeenCalledTimes(1);
    expect(reportHandledError.mock.calls[0][1]).toBe('app-route');
  });

  /** Never the error's own message: backend messages are written for users and can quote the data
   *  that failed validation. */
  it('does not pass the error message anywhere except the exception itself', () => {
    render(
      <ErrorBoundary context="app-route">
        <Boom />
      </ErrorBoundary>
    );

    const [, context] = reportHandledError.mock.calls[0];
    expect(context).not.toMatch(/blew up/);
    expect(screen.queryByText(/blew up/)).not.toBeInTheDocument();
  });

  it('recovers when the user retries and the child no longer throws', async () => {
    const user = userEvent.setup();

    // The flag lives outside the component on purpose. The boundary unmounts its child when it
    // catches, so anything held in that child's own state is gone by the time "Try again" remounts
    // it -- which is exactly the situation this models: a transient failure (a bad response, a
    // race) that has since resolved, where a retry genuinely should work.
    let failing = true;
    function Flaky() {
      if (failing) throw new Error('first render fails');
      return <p>recovered content</p>;
    }

    render(
      <ErrorBoundary context="test">
        <Flaky />
      </ErrorBoundary>
    );

    expect(screen.getByRole('alert')).toBeInTheDocument();

    failing = false;
    await user.click(screen.getByRole('button', { name: /try again/i }));

    expect(await screen.findByText('recovered content')).toBeInTheDocument();
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
