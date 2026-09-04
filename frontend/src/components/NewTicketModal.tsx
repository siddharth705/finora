import { useState } from 'react';
import { X, Paperclip } from 'lucide-react';
import { supportApi, type SupportTicketCategory, type SupportTicketDetail } from '../api/endpoints';

// Mirrors SupportAttachmentUpload's own allow-list and size ceiling exactly -- the accept
// attribute and this message are a convenience so most users never hit the server's 400/415 at
// all, not a substitute for it (the server re-validates every byte regardless of what the picker
// let through).
const MAX_ATTACHMENT_BYTES = 5 * 1024 * 1024;
const ACCEPT = '.pdf,.png,.jpg,.jpeg,.txt,application/pdf,image/png,image/jpeg,text/plain';

const CATEGORIES: { value: SupportTicketCategory; label: string }[] = [
  { value: 'STATEMENT_IMPORT', label: 'Statement import' },
  { value: 'CATEGORIZATION', label: 'Transaction categorization' },
  { value: 'ACCOUNT_LINKING', label: 'Account linking' },
  { value: 'DATA_ACCURACY', label: 'Data accuracy' },
  { value: 'TECHNICAL_ISSUE', label: 'Technical issue' },
  { value: 'OTHER', label: 'Something else' },
];

/**
 * Support, Help & Feedback v1, Phase 8. Submits straight to SupportTicketController.create() --
 * multipart, so the attachment (if any) rides in the same request as the rest of the form rather
 * than a separate upload step.
 *
 * Deliberately NOT reachable from the public /contact page: that page is unauthenticated
 * (marketing/compliance surface, reachable by prospects), and every ticket-creation call requires
 * CurrentUser -- see SupportTicketController. This modal only ever mounts inside the authenticated
 * app shell (My Tickets, TopBar's Help menu), where a caller is guaranteed.
 */
export function NewTicketModal({ onClose, onCreated }: { onClose: () => void; onCreated: (ticket: SupportTicketDetail) => void }) {
  const [category, setCategory] = useState<SupportTicketCategory>('STATEMENT_IMPORT');
  const [subject, setSubject] = useState('');
  const [description, setDescription] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [fileError, setFileError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canSave = subject.trim().length > 0 && description.trim().length > 0 && !fileError && !saving;

  function handleFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const picked = e.target.files?.[0] ?? null;
    if (picked && picked.size > MAX_ATTACHMENT_BYTES) {
      setFile(null);
      setFileError('Attachments are limited to 5 MB.');
      return;
    }
    setFileError(null);
    setFile(picked);
  }

  async function save() {
    if (!canSave) return;
    setSaving(true);
    setError(null);
    try {
      const ticket = await supportApi.create({
        category,
        subject: subject.trim(),
        description: description.trim(),
        file,
      });
      onCreated(ticket);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not submit the ticket.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-lg max-h-[85vh] overflow-y-auto p-5 pointer-events-auto">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-ink text-sm">New support ticket</h3>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>

          {error && <p className="text-danger text-xs mb-3">{error}</p>}

          <div className="space-y-3 text-sm">
            <div>
              <label htmlFor="new-ticket-category" className="block text-[11px] uppercase text-muted mb-1">Category</label>
              <select
                id="new-ticket-category"
                value={category}
                onChange={(e) => setCategory(e.target.value as SupportTicketCategory)}
                className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full"
              >
                {CATEGORIES.map((c) => <option key={c.value} value={c.value}>{c.label}</option>)}
              </select>
            </div>
            <div>
              <label htmlFor="new-ticket-subject" className="block text-[11px] uppercase text-muted mb-1">Subject</label>
              <input
                id="new-ticket-subject"
                value={subject}
                onChange={(e) => setSubject(e.target.value)}
                placeholder="A short summary of the issue"
                className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full"
              />
            </div>
            <div>
              <label htmlFor="new-ticket-description" className="block text-[11px] uppercase text-muted mb-1">Description</label>
              <textarea
                id="new-ticket-description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                rows={5}
                placeholder="What happened, and what did you expect instead?"
                className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full resize-none"
              />
            </div>
            <div>
              <label htmlFor="new-ticket-file" className="block text-[11px] uppercase text-muted mb-1">
                Attachment <span className="normal-case text-muted">(optional — PDF, PNG, JPEG or text, up to 5 MB)</span>
              </label>
              <label
                htmlFor="new-ticket-file"
                className="flex items-center gap-2 border border-dashed border-border rounded-lg px-3 py-2.5 text-xs text-muted cursor-pointer hover:border-primary/40 hover:text-ink"
              >
                <Paperclip size={14} className="flex-shrink-0" />
                {file ? file.name : 'Choose a file (e.g. a screenshot of the problem)'}
              </label>
              <input id="new-ticket-file" type="file" accept={ACCEPT} onChange={handleFileChange} className="hidden" />
              {fileError && <p className="text-[11px] text-danger mt-1">{fileError}</p>}
            </div>
          </div>

          <div className="flex gap-3 mt-5">
            <button
              type="button"
              onClick={save}
              disabled={!canSave}
              className="bg-primary text-on-primary hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold disabled:opacity-50"
            >
              {saving ? 'Submitting…' : 'Submit ticket'}
            </button>
            <button type="button" onClick={onClose} className="border border-border text-ink px-4 py-2 rounded-lg text-xs font-semibold">
              Cancel
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
