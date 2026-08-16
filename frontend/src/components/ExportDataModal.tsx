import { useState } from 'react';
import { X } from 'lucide-react';
import { accountLifecycleApi } from '../api/endpoints';

/**
 * "Download My Data" (Phase C) -- current password only, same re-auth tier as
 * DeactivateAccountModal, not the OTP tier DeleteAccountModal uses (see
 * AccountLifecycleDtos.ExportDataRequest's own doc comment on the backend for why).
 *
 * There's no partial-progress signal available while the export streams (see
 * DataExportService.writeZip), so the button's own disabled "Preparing…" state is the whole
 * loading UI -- honest given what's actually knowable, rather than a fake progress bar.
 * accountLifecycleApi.exportData() triggers the browser download itself on success, so there is
 * no separate success step here; the modal just closes.
 */
export function ExportDataModal({ onClose }: { onClose: () => void }) {
  const [currentPassword, setCurrentPassword] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    if (currentPassword.length === 0) { setError('Enter your current password.'); return; }
    setSubmitting(true);
    setError(null);
    try {
      await accountLifecycleApi.exportData(currentPassword);
      onClose();
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Could not prepare your export. Please try again.');
      setSubmitting(false);
    }
  }

  return (
    <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-30" onClick={submitting ? undefined : onClose}>
      <div className="bg-card rounded-xl2 shadow-card p-6 w-[420px] max-w-[90vw]" onClick={(e) => e.stopPropagation()}>
        <div className="flex items-center justify-between mb-4">
          <h2 className="font-serif text-lg font-semibold text-ink">Export My Data</h2>
          <button onClick={onClose} disabled={submitting} className="text-muted hover:text-ink disabled:opacity-50" aria-label="Close">
            <X size={18} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="space-y-3">
          <p className="text-xs text-muted">
            Downloads a ZIP of everything in your account — accounts, transactions, budgets,
            goals, and your original bank statement files — along with a manifest explaining
            exactly what's included. Enter your current password to confirm.
          </p>
          <div>
            <label htmlFor="export-current-password" className="block text-xs uppercase text-muted mb-1">
              Current password
            </label>
            <input
              id="export-current-password"
              type="password"
              autoComplete="current-password"
              value={currentPassword}
              onChange={(e) => { setCurrentPassword(e.target.value); setError(null); }}
              disabled={submitting}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm disabled:opacity-50"
            />
          </div>
          {error && <p className="text-danger text-xs">{error}</p>}
          <div className="flex justify-end gap-2 pt-2">
            <button
              type="button"
              onClick={onClose}
              disabled={submitting}
              className="border border-border rounded-lg px-4 py-2 text-xs uppercase font-medium text-ink hover:bg-black/5 disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={submitting}
              className="bg-primary text-white hover:bg-primary-dark disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
            >
              {submitting ? 'Preparing your export…' : 'Export My Data'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}
