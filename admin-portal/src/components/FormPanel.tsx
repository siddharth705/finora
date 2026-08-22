import type { FormEvent, ReactNode } from 'react';
import { X } from 'lucide-react';

/**
 * Shared wrapper for every create/edit form in this app (Banks, Global Rules, Users, Roles &
 * Permissions) -- all five previously hand-rolled the identical title/close-button header, error
 * banner, and Cancel/Submit footer around their own field grid. Pulling that repetition into one
 * component means the next admin module (Merchant Intelligence, Rule Engine, ...) gets this for
 * free instead of copy-pasting it a sixth time. `children` is just the field grid -- callers keep
 * full control over their own inputs/selects, this only standardizes what wraps them.
 */
export function FormPanel({
  title, onCancel, onSubmit, error, submitting, submitLabel, children,
}: {
  title: string;
  onCancel: () => void;
  onSubmit: (e: FormEvent<HTMLFormElement>) => void;
  error?: string | null;
  submitting: boolean;
  submitLabel: string;
  children: ReactNode;
}) {
  return (
    <form
      onSubmit={onSubmit}
      className="bg-card border border-border rounded-xl2 shadow-card p-5 space-y-4"
    >
      <div className="flex items-center justify-between">
        <h3 className="font-semibold text-ink">{title}</h3>
        <button type="button" onClick={onCancel} className="text-muted hover:text-ink">
          <X size={16} />
        </button>
      </div>
      {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2">{error}</p>}
      {children}
      <div className="flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="text-sm font-medium text-muted px-3.5 py-2 rounded-lg hover:bg-bg">
          Cancel
        </button>
        <button
          type="submit"
          disabled={submitting}
          className="bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2 disabled:opacity-50"
        >
          {submitLabel}
        </button>
      </div>
    </form>
  );
}
