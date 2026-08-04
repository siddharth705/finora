import { Component, type ErrorInfo, type ReactNode } from 'react';
import { reportHandledError } from '../lib/monitoring';

/**
 * Catches a render error in the subtree below it and shows a recovery panel instead of letting
 * React unmount the whole tree.
 *
 * Why this exists: without a boundary anywhere, any error thrown during render takes out the
 * entire app and leaves a blank white page. That exact symptom already shipped once, from a
 * routing defect, and nothing reported it -- the user simply saw nothing. A blank page is also the
 * worst possible failure for a finance app, because it is indistinguishable from "my data is
 * gone".
 *
 * DELIBERATELY NOT WRAPPED AROUND THE WHOLE APP. Placed around route content only, so the chrome
 * the user needs to escape with -- sidebar, top bar, navigation -- keeps rendering and working. A
 * boundary at the root would catch more, but it would replace the entire screen with an apology
 * and leave the user with nowhere to go but the browser's back button. Scoping it also stops a
 * broken page from silently masking itself: the failure stays visibly attached to the page that
 * caused it.
 *
 * Still a class component: `componentDidCatch`/`getDerivedStateFromError` have no hook equivalent.
 * This is the one place in the codebase where that is the correct choice rather than a leftover.
 */

interface Props {
  children: ReactNode;
  /** Names the boundary in the crash report, so "which part broke" is answerable without a stack. */
  context: string;
}

interface State {
  hasError: boolean;
}

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(): State {
    return { hasError: true };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    // Only the component stack is attached, never the error message as `extra` -- see
    // reportHandledError's own comment on why a caught value's message can quote user data.
    reportHandledError(error, this.props.context);
    // Kept so the error is still visible in the browser console during local development, where
    // no DSN is configured and reportHandledError is a no-op.
    console.error(`Render error in ${this.props.context}:`, error, info.componentStack);
  }

  private reset = () => {
    this.setState({ hasError: false });
  };

  render() {
    if (!this.state.hasError) return this.props.children;

    return (
      <div role="alert" className="bg-card rounded-xl2 shadow-card p-8 text-center max-w-md mx-auto my-12">
        <p className="text-ink font-medium">This page didn't load correctly</p>
        <p className="text-muted text-sm mt-1">
          Nothing has been lost — your accounts and transactions are unaffected. Try again, or use
          the menu to go somewhere else.
        </p>
        <button
          onClick={this.reset}
          className="mt-5 bg-primary text-white hover:bg-primary-dark rounded-lg px-4 py-2 text-xs uppercase font-medium"
        >
          Try again
        </button>
      </div>
    );
  }
}
