/**
 * Replaces the browser's own native `confirm()` for destructive/state-changing actions across the
 * admin portal -- that dialog renders as unstyled OS/browser chrome (literally titled with the
 * page's own origin), which reads as broken rather than as part of the product. Same fix, same
 * component shape, as the user-facing app's own ConfirmDialog (frontend/src/design-system).
 *
 * Not a global singleton or a hook-based imperative API: the caller owns the "what am I
 * confirming" state (typically `useState<T | null>`) and conditionally renders this component,
 * same convention EntityDrawer's own overlay/backdrop pattern already establishes here.
 */
export function ConfirmDialog({
  title, message, confirmLabel = 'Confirm', cancelLabel = 'Cancel', danger, busy, onConfirm, onCancel,
}: {
  title: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  /** Red confirm button -- for a delete/revoke/suspend action, as opposed to a neutral confirmation. */
  danger?: boolean;
  /** Disables both buttons and swaps the confirm label to a busy state while the action is in flight. */
  busy?: boolean;
  onConfirm: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center" role="dialog" aria-modal="true" aria-labelledby="confirm-dialog-title">
      <div className="absolute inset-0 bg-black/30" onClick={busy ? undefined : onCancel} />
      <div className="relative bg-card border border-border rounded-xl2 shadow-soft w-full max-w-sm p-5 space-y-4">
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
  );
}
