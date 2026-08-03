import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { User, ShieldCheck, CheckCircle2 } from 'lucide-react';
import { userApi } from '../api/endpoints';
import { maskPhone } from '../lib/maskPhone';
import { initials, formatMonthYear, formatRelativeTime, SectionCard, VerifiedBadge, SaveStatus } from '../components/AccountUI';

// Profile vs Settings: Profile is "who you are" (identity facts, editable personal info) --
// Settings is "how the app behaves for you" (preferences, security actions, AI behavior, data).
// Security here is deliberately read-only -- a summary with a link to Settings' Security section,
// not a duplicate set of action buttons. See Settings.tsx's own top-of-file comment for the same
// "real capability only" scope discipline this page follows: no profile picture upload, no
// subscription/plan display, no fabricated "last login" or "security score" -- none of those
// exist on the backend yet.

export default function Profile() {
  const [email, setEmail] = useState('');
  const [fullName, setFullName] = useState('');
  const [savedFullName, setSavedFullName] = useState('');
  const [phoneNumber, setPhoneNumber] = useState('');
  const [phoneVerified, setPhoneVerified] = useState(false);
  const [createdAt, setCreatedAt] = useState<string | null>(null);
  const [passwordChangedAt, setPasswordChangedAt] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState(false);

  const [saving, setSaving] = useState(false);
  const [justSaved, setJustSaved] = useState(false);
  const [error, setError] = useState(false);

  const dirty = fullName.trim() !== savedFullName;

  useEffect(() => {
    userApi.get().then((u) => {
      setEmail(u.email);
      setFullName(u.fullName);
      setSavedFullName(u.fullName);
      setPhoneNumber(u.phoneNumber);
      setPhoneVerified(u.phoneVerified);
      setCreatedAt(u.createdAt);
      setPasswordChangedAt(u.passwordChangedAt);
      setLoading(false);
    }).catch(() => {
      setLoadError(true);
      setLoading(false);
    });
  }, []);

  async function save() {
    setSaving(true);
    setError(false);
    try {
      const updated = await userApi.update({ fullName: fullName.trim() });
      setFullName(updated.fullName);
      setSavedFullName(updated.fullName);
      setJustSaved(true);
      setTimeout(() => setJustSaved(false), 2000);
    } catch {
      setError(true);
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <p className="text-muted">Loading…</p>;

  if (loadError) return <p className="text-muted">Couldn't load your profile — please try again later.</p>;

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h1 className="font-serif text-2xl font-semibold text-ink">Profile</h1>
        <p className="text-sm text-muted mt-1">Your personal information and account identity.</p>
      </div>

      {/* Reflects the SAVED name, not an in-progress edit below, so it never flickers ahead of
          what the "Unsaved changes" indicator in Personal Information is reporting. */}
      <div className="bg-card rounded-xl2 p-6 shadow-card border border-border flex items-center gap-4">
        <div className="w-14 h-14 rounded-full bg-primary flex items-center justify-center text-white text-lg font-semibold flex-shrink-0">
          {initials(savedFullName)}
        </div>
        <div className="min-w-0">
          <p className="font-serif text-xl font-semibold text-ink truncate">{savedFullName || 'Your account'}</p>
          <p className="text-sm text-muted truncate">{email}</p>
          <div className="flex items-center gap-3 mt-1.5 text-xs text-muted flex-wrap">
            {phoneVerified && (
              <span className="inline-flex items-center gap-1 text-success"><CheckCircle2 size={12} /> Phone verified</span>
            )}
            <span>Member since {formatMonthYear(createdAt)}</span>
          </div>
        </div>
      </div>

      <SectionCard icon={<User size={18} />} title="Personal Information" subtitle="Your name, email, and phone on file">
        <div className="grid md:grid-cols-2 gap-4">
          <div>
            <label htmlFor="profile-full-name" className="block text-xs uppercase text-muted mb-1">Full name</label>
            <input
              id="profile-full-name"
              value={fullName}
              onChange={(e) => setFullName(e.target.value)}
              className="bg-card text-ink w-full border border-border rounded-lg px-3 py-2 text-sm"
            />
          </div>
          <div>
            <label htmlFor="profile-email" className="block text-xs uppercase text-muted mb-1">Email</label>
            <input id="profile-email" value={email} readOnly className="text-ink w-full border border-border rounded-lg px-3 py-2 text-sm bg-black/5" />
          </div>
          <div>
            <label htmlFor="profile-phone" className="block text-xs uppercase text-muted mb-1">Phone number</label>
            <div className="flex items-center gap-2">
              <input id="profile-phone" value={phoneNumber || '—'} readOnly className="text-ink w-full border border-border rounded-lg px-3 py-2 text-sm bg-black/5" />
              {phoneVerified && <VerifiedBadge />}
            </div>
          </div>
          <div>
            <label htmlFor="profile-member-since" className="block text-xs uppercase text-muted mb-1">Member since</label>
            <input id="profile-member-since" value={formatMonthYear(createdAt)} readOnly className="text-ink w-full border border-border rounded-lg px-3 py-2 text-sm bg-black/5" />
          </div>
        </div>
        <div className="flex items-center justify-end gap-3 mt-5 pt-4 border-t border-border">
          <SaveStatus dirty={dirty} saving={saving} justSaved={justSaved} error={error} />
          <button
            onClick={save}
            disabled={saving || !dirty || !fullName.trim()}
            className="bg-primary text-white hover:bg-primary-dark disabled:opacity-50 rounded-lg px-4 py-2 text-xs uppercase font-medium"
          >
            {saving ? 'Saving…' : 'Save changes'}
          </button>
        </div>
      </SectionCard>

      <SectionCard icon={<ShieldCheck size={18} />} title="Security Overview" subtitle="A quick summary — manage these in Settings">
        <div className="border-b border-border py-3 text-sm flex items-center justify-between">
          <div>
            <p className="text-ink font-medium">Password</p>
            <p className="text-muted text-xs mt-0.5">
              {formatRelativeTime(passwordChangedAt) ? `Last changed ${formatRelativeTime(passwordChangedAt)}` : 'Never changed'}
            </p>
          </div>
        </div>
        <div className="flex items-center justify-between py-3 text-sm">
          <div>
            <p className="text-ink font-medium">Phone verification</p>
            <p className="text-muted text-xs">{phoneNumber ? maskPhone(phoneNumber) : 'No phone number on file'}</p>
          </div>
          {phoneVerified ? <VerifiedBadge /> : <span className="text-xs text-muted flex-shrink-0">Not verified</span>}
        </div>
        <Link
          to="/app/settings"
          className="inline-block mt-4 text-xs font-medium text-primary bg-primary-light rounded-lg px-3 py-1.5"
        >
          Manage Security →
        </Link>
      </SectionCard>
    </div>
  );
}
