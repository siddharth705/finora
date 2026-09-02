import { useEffect, useRef } from 'react';

/** Tab stops inside the dialog. `:not([disabled])` matters because `busy` disables both buttons,
 *  which takes them out of the tab order and can leave the panel with nothing focusable in it. */
const FOCUSABLE_SELECTOR =
  'button:not([disabled]), [href], input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])';

/**
 * Replaces the browser's own native `confirm()` for destructive/discard actions across the app --
 * that dialog renders as unstyled OS/browser chrome (literally titled with the page's own origin,
 * e.g. "app.fynora.net says"), which reads as broken rather than as part of the product.
 *
 * Not a global singleton or a hook-based imperative API: the caller owns the "what am I
 * confirming" state (typically `useState<T | null>`) and conditionally renders this component,
 * same convention every other modal on these pages already uses (see StatementHistory.tsx's own
 * ReimportPasswordModal/StatementDetailModal). Escape cancels, same as a native confirm()'s own
 * Esc-dismisses-the-dialog behavior -- this replaces that dialog, so it should keep that much of it.
 */
export function ConfirmDialog({
  title, message, confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger, busy, onConfirm, onCancel,
}: {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Red confirm button -- for a delete/discard action, as opposed to a neutral confirmation. */
  danger?: boolean;
  /** Disables both buttons and swaps the confirm label to a busy state while the action is in flight. */
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  const panelRef = useRef<HTMLDivElement>(null);

  /**
   * Move focus into the dialog on open, and put it back where it came from on close.
   *
   * Deliberately lands on the FIRST focusable control, which is Cancel -- WAI-ARIA's guidance for a
   * destructive confirmation is to focus the least destructive action, so a reflexive Enter dismisses
   * rather than deletes. Runs once: `busy` flipping mid-action must not yank focus around underneath
   * someone, and while busy there is nothing focusable to move to anyway.
   */
  useEffect(() => {
    const previouslyFocused = document.activeElement as HTMLElement | null;
    const panel = panelRef.current;
    (panel?.querySelector<HTMLElement>(FOCUSABLE_SELECTOR) ?? panel)?.focus();
    return () => previouslyFocused?.focus?.();
  }, []);

  /**
   * Escape-to-cancel (matching the native confirm() this replaces), plus a real focus trap.
   *
   * Without the trap this dialog was only visually modal: the backdrop swallows mouse clicks, but Tab
   * walked straight out of the dialog and into the controls it was covering, so a destructive
   * confirmation could sit open while the user operated the very screen it was asking about. That is
   * not hypothetical -- it is how a discard confirmation could end up stacked over the import summary
   * screen, with "Discard" then firing against an already-finalized session (see the step gating in
   * pages/Import.tsx, which remains as defence in depth).
   */
  useEffect(() => {
    function handleKeyDown(e: KeyboardEvent) {
      if (e.key === 'Escape') {
        // Escape stays inert while busy -- the action is already in flight and cancelling the dialog
        // would not cancel it. The Tab trap below deliberately does NOT get the same exemption:
        // that is precisely when focus must not escape to the page behind.
        if (!busy) onCancel();
        return;
      }
      if (e.key !== 'Tab') return;

      const panel = panelRef.current;
      if (!panel) return;
      const focusable = Array.from(panel.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR));

      // Nothing focusable inside (both buttons disabled while busy): hold focus on the panel itself
      // rather than letting Tab fall through to the page behind the backdrop.
      if (focusable.length === 0) {
        e.preventDefault();
        panel.focus();
        return;
      }

      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      const active = document.activeElement;
      const outside = !panel.contains(active);

      if (e.shiftKey && (active === first || outside)) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && (active === last || outside)) {
        e.preventDefault();
        first.focus();
      }
    }
    document.addEventListener('keydown', handleKeyDown);
    return () => document.removeEventListener('keydown', handleKeyDown);
  }, [busy, onCancel]);

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={busy ? undefined : onCancel} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div
          ref={panelRef}
          role="alertdialog"
          aria-modal="true"
          aria-labelledby="confirm-dialog-title"
          // Focusable as a last resort only (never a Tab stop of its own): while `busy` disables both
          // buttons, this is the one thing left inside for the trap to park focus on.
          tabIndex={-1}
          className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5 pointer-events-auto space-y-4 focus:outline-none"
        >
          <h3 id="confirm-dialog-title" className="font-semibold text-ink text-sm">{title}</h3>
          <p className="text-sm text-muted">{message}</p>
          <div className="flex justify-end gap-2">
            <button
              type="button"
              onClick={onCancel}
              disabled={busy}
              className="text-xs font-medium text-muted hover:text-ink px-3 py-2 rounded-lg disabled:opacity-40"
            >
              {cancelLabel}
            </button>
            <button
              type="button"
              onClick={onConfirm}
              disabled={busy}
              className={`text-xs font-semibold text-white rounded-lg px-3.5 py-2 disabled:opacity-40 ${danger ? 'bg-danger' : 'bg-primary'}`}
            >
              {busy ? 'Working…' : confirmLabel}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
