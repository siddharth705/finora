import { useState } from 'react';
import { useLocation } from 'react-router-dom';
import { X, Check } from 'lucide-react';
import { feedbackApi, type FeedbackType } from '../api/endpoints';
import { contextForPath } from '../lib/feedbackContext';

const TYPES: { value: FeedbackType; label: string }[] = [
  { value: 'BUG', label: 'Something’s broken' },
  { value: 'FEATURE_REQUEST', label: 'A feature I’d like' },
  { value: 'IMPROVEMENT', label: 'An improvement idea' },
  { value: 'GENERAL', label: 'General feedback' },
];

/**
 * Support, Help & Feedback v1, Phase 8. Replaces TopBar's old "Send feedback" mailto link --
 * FeedbackController.submit() has existed since the backend phases with nothing calling it. Context
 * is derived automatically from the current route (contextForPath) rather than asked of the user:
 * one widget, mounted once, matching "mount points, not a bespoke form per page."
 */
export function FeedbackModal({ onClose }: { onClose: () => void }) {
  const location = useLocation();
  const context = contextForPath(location.pathname);

  const [type, setType] = useState<FeedbackType>('GENERAL');
  const [message, setMessage] = useState('');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [sent, setSent] = useState(false);

  const canSave = message.trim().length > 0 && !saving;

  async function save() {
    if (!canSave) return;
    setSaving(true);
    setError(null);
    try {
      await feedbackApi.submit({ type, context, message: message.trim() });
      setSent(true);
    } catch (e: any) {
      setError(e.response?.data?.message ?? 'Could not send this feedback.');
    } finally {
      setSaving(false);
    }
  }

  return (
    <>
      <div className="fixed inset-0 bg-black/40 z-30" onClick={onClose} />
      <div className="fixed inset-0 z-40 flex items-center justify-center p-4 pointer-events-none">
        <div className="bg-card border border-border rounded-xl2 shadow-soft w-full max-w-md p-5 pointer-events-auto">
          <div className="flex items-center justify-between mb-4">
            <h3 className="font-semibold text-ink text-sm">Send feedback</h3>
            <button type="button" onClick={onClose} aria-label="Close" className="text-muted hover:text-ink">
              <X size={18} />
            </button>
          </div>

          {sent ? (
            <div className="flex flex-col items-center text-center gap-2 py-4">
              <div className="w-10 h-10 rounded-full bg-success-bg flex items-center justify-center">
                <Check size={18} className="text-success" />
              </div>
              <p className="text-sm font-medium text-ink">Thanks for the feedback</p>
              <p className="text-xs text-muted">We read every submission.</p>
              <button type="button" onClick={onClose} className="mt-2 text-xs font-medium text-primary hover:underline">
                Close
              </button>
            </div>
          ) : (
            <>
              {error && <p className="text-danger text-xs mb-3">{error}</p>}
              <div className="space-y-3 text-sm">
                <div>
                  <label htmlFor="feedback-type" className="block text-[11px] uppercase text-muted mb-1">What kind of feedback?</label>
                  <select
                    id="feedback-type"
                    value={type}
                    onChange={(e) => setType(e.target.value as FeedbackType)}
                    className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full"
                  >
                    {TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
                  </select>
                </div>
                <div>
                  <label htmlFor="feedback-message" className="block text-[11px] uppercase text-muted mb-1">Your feedback</label>
                  <textarea
                    id="feedback-message"
                    value={message}
                    onChange={(e) => setMessage(e.target.value)}
                    rows={4}
                    placeholder="What's on your mind?"
                    className="bg-card text-ink border border-border rounded-lg px-3 py-2 text-sm w-full resize-none"
                  />
                </div>
              </div>

              <div className="flex gap-3 mt-5">
                <button
                  type="button"
                  onClick={save}
                  disabled={!canSave}
                  className="bg-primary text-on-primary hover:bg-primary-dark px-4 py-2 rounded-lg text-xs font-semibold disabled:opacity-50"
                >
                  {saving ? 'Sending…' : 'Send feedback'}
                </button>
                <button type="button" onClick={onClose} className="border border-border text-ink px-4 py-2 rounded-lg text-xs font-semibold">
                  Cancel
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </>
  );
}
