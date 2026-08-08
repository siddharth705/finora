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

/** A lazy route whose chunk the last deploy replaced -- the failure the user app hit in production. */
function StaleChunk(): React.ReactElement {
  throw new Error(
    'Failed to fetch dynamically imported module: https://admin.example.com/assets/Dashboard-a1b2c3.js'
  );
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

  /**
   * The admin portal has the same lazy routes, the same boundary and the same reset as the user
   * app, so it had the same unrecoverable "Try again" waiting for the next deploy. Fixed here too
   * rather than after an operator loses a session to it.
   */
  describe('when a route chunk no longer exists on the server', () => {
    let reload: ReturnType<typeof vi.fn>;

    beforeEach(() => {
      window.sessionStorage.clear();
      reload = vi.fn();
      Object.defineProperty(window, 'location', {
        configurable: true,
        value: { ...window.location, reload },
      });
    });

    afterEach(() => {
      window.sessionStorage.clear();
    });

    it('reloads the document instead of offering a retry that cannot work', () => {
      render(
        <ErrorBoundary context="root">
          <StaleChunk />
        </ErrorBoundary>
      );

      expect(reload).toHaveBeenCalledTimes(1);
    });

    it('does not report a failure it is recovering from', () => {
      render(
        <ErrorBoundary context="root">
          <StaleChunk />
        </ErrorBoundary>
      );

      expect(reportHandledError).not.toHaveBeenCalled();
    });

    /** Once a reload has been tried, the same failure means the FRESH html failed too -- a broken
     *  deploy, not a stale tab. The operator gets the panel and the team gets the report. */
    it('shows the panel and reports when a reload has already been attempted', () => {
      render(
        <ErrorBoundary context="root">
          <StaleChunk />
        </ErrorBoundary>
      );
      reload.mockClear();

      render(
        <ErrorBoundary context="root">
          <StaleChunk />
        </ErrorBoundary>
      );

      expect(reload).not.toHaveBeenCalled();
      expect(screen.getAllByRole('alert').length).toBeGreaterThan(0);
      expect(reportHandledError).toHaveBeenCalledTimes(1);
    });

    it('leaves ordinary render errors on the retry path', () => {
      render(
        <ErrorBoundary context="root">
          <Boom />
        </ErrorBoundary>
      );

      expect(reload).not.toHaveBeenCalled();
      expect(screen.getByRole('alert')).toBeInTheDocument();
      expect(reportHandledError).toHaveBeenCalledTimes(1);
    });
  });
});
