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

/** The production failure: a lazy route whose chunk the last deploy replaced. */
function StaleChunk(): React.ReactElement {
  throw new Error(
    'Failed to fetch dynamically imported module: https://app.example.com/assets/Login-00kC5-u3.js'
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

  /** A finance app going blank reads as "my data is gone", so the copy has to say otherwise. */
  it('tells the user their financial data is unaffected', () => {
    render(
      <ErrorBoundary context="test">
        <Boom />
      </ErrorBoundary>
    );

    expect(screen.getByText(/nothing has been lost/i)).toBeInTheDocument();
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
   * A stale route chunk is the one error the retry button cannot fix -- retrying re-requests the
   * same missing file. It shipped as an unrecoverable "Try again" that never worked: the user in
   * the incident was stuck on /login with no way forward but a manual hard refresh.
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

    /** A stale chunk right after a deploy is expected and self-healing. Reporting it would spike
     *  the crash reporter on every release with an error nobody should act on. */
    it('does not report a failure it is recovering from', () => {
      render(
        <ErrorBoundary context="root">
          <StaleChunk />
        </ErrorBoundary>
      );

      expect(reportHandledError).not.toHaveBeenCalled();
    });

    /**
     * The other half, and the one that matters for safety: once a reload has already been tried,
     * the same failure means the FRESH html failed too. That is a broken deploy, not a stale tab.
     * The user gets the panel and the team gets the report, instead of an endless reload cycle.
     */
    it('shows the panel and reports when a reload has already been attempted', () => {
      render(
        <ErrorBoundary context="root">
          <StaleChunk />
        </ErrorBoundary>
      );
      expect(reload).toHaveBeenCalledTimes(1);
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

    /** An ordinary bug must never trigger a reload -- that would swap a readable error for a page
     *  that reloads and then fails identically. */
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
