import { useEffect, useState, type ReactNode } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ShieldCheck, ShieldOff, KeyRound, Copy, Check, Loader2 } from 'lucide-react';
import QRCode from 'qrcode';
import { adminMfaApi, userApi } from '../api/endpoints';
import { AUTH_MFA_NOT_AVAILABLE, AUTH_MFA_INVALID_CODE } from '../api/errorCodes';
import { useNotify } from '../context/NotificationContext';
import { PasswordInput } from './PasswordInput';
import { GoogleReauthPrompt } from './GoogleReauthPrompt';

function errorCodeOf(err: any): string | undefined {
  return err?.response?.data?.errorCode;
}

function Card({ children }: { children: ReactNode }) {
  return <div className="bg-card border border-border rounded-xl2 shadow-card p-6 space-y-4">{children}</div>;
}

function CardHeader({ enabled }: { enabled: boolean }) {
  return (
    <div className="flex items-center gap-2.5">
      {enabled ? <ShieldCheck size={18} className="text-success" /> : <KeyRound size={18} className="text-primary" />}
      <h3 className="font-semibold text-ink">Two-factor authentication</h3>
    </div>
  );
}

/** Renders provisioningUri (an otpauth:// URI) as a scannable PNG. QRCode.toDataURL is async
 *  (it does real work encoding the QR matrix), so this starts blank and fills in once ready --
 *  never long enough in practice to need a loading state of its own. */
function ProvisioningQrCode({ uri }: { uri: string }) {
  const [dataUrl, setDataUrl] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    QRCode.toDataURL(uri, { width: 200, margin: 1 })
      .then((url) => { if (!cancelled) setDataUrl(url); })
      .catch(() => { /* falls back to the secret's manual-entry text below; nothing to show here */ });
    return () => { cancelled = true; };
  }, [uri]);

  if (!dataUrl) return <div className="w-[200px] h-[200px] bg-bg rounded-lg animate-pulse" />;
  return <img src={dataUrl} alt="Scan this QR code with your authenticator app" width={200} height={200} className="rounded-lg border border-border" />;
}

function CopyButton({ text, label }: { text: string; label: string }) {
  const [copied, setCopied] = useState(false);
  const notify = useNotify();

  // Same try/catch as Diagnostics.tsx's CopyDiagnosticsButton -- navigator.clipboard.writeText()
  // rejects in several realistic conditions (unfocused tab, denied permission, plain-HTTP
  // deployment), and silently doing nothing here is worse than usual: these are the only codes
  // that will ever exist in the clear.
  async function handleCopy() {
    try {
      await navigator.clipboard.writeText(text);
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    } catch {
      notify.error('Could not copy to clipboard -- your browser may have blocked it.');
    }
  }

  return (
    <button
      type="button"
      onClick={handleCopy}
      className="inline-flex items-center gap-1.5 text-xs font-medium text-ink border border-border rounded-lg px-3 py-1.5 hover:bg-bg"
    >
      {copied ? <Check size={13} className="text-success" /> : <Copy size={13} />}
      {copied ? 'Copied' : label}
    </button>
  );
}

type EnrollStep = 'intro' | 'scan' | 'recovery-codes';

function EnrollFlow({ onEnrolled }: { onEnrolled: () => void }) {
  const notify = useNotify();
  const [step, setStep] = useState<EnrollStep>('intro');
  const [secret, setSecret] = useState('');
  const [provisioningUri, setProvisioningUri] = useState('');
  const [code, setCode] = useState('');
  const [codeError, setCodeError] = useState<string | null>(null);
  const [recoveryCodes, setRecoveryCodes] = useState<string[]>([]);

  const enrollMutation = useMutation({
    mutationFn: () => adminMfaApi.enroll(),
    onSuccess: (res) => {
      setSecret(res.secret);
      setProvisioningUri(res.provisioningUri);
      setStep('scan');
    },
    onError: () => notify.error('Could not start enrollment. Please try again.'),
  });

  const confirmMutation = useMutation({
    mutationFn: () => adminMfaApi.confirm(code.trim()),
    onSuccess: (res) => {
      setRecoveryCodes(res.recoveryCodes);
      setStep('recovery-codes');
    },
    onError: (err: any) => {
      setCodeError(
        errorCodeOf(err) === AUTH_MFA_INVALID_CODE
          ? "That code didn't work. Check your authenticator app and try again."
          : (err?.response?.data?.message ?? 'Could not confirm that code. Please try again.'));
      setCode('');
    },
  });

  if (step === 'intro') {
    return (
      <>
        <p className="text-sm text-muted">
          Add an extra layer of security to your account. Once turned on, you'll need a code from
          an authenticator app (like Google Authenticator or Authy) every time you sign in.
        </p>
        <button
          type="button"
          onClick={() => enrollMutation.mutate()}
          disabled={enrollMutation.isPending}
          className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2.5 disabled:opacity-50"
        >
          {enrollMutation.isPending && <Loader2 size={14} className="animate-spin" />}
          Set up two-factor authentication
        </button>
      </>
    );
  }

  if (step === 'scan') {
    return (
      <div className="space-y-4">
        <p className="text-sm text-muted">
          Scan this code with your authenticator app, then enter the 6-digit code it shows you.
        </p>
        <div className="flex flex-col sm:flex-row gap-5">
          <ProvisioningQrCode uri={provisioningUri} />
          <div className="flex-1 min-w-0 space-y-1.5">
            <p className="text-xs text-muted">Can't scan it? Enter this key manually instead:</p>
            <div className="flex items-center gap-2">
              <code className="text-xs font-mono bg-bg border border-border rounded-lg px-2.5 py-1.5 break-all">{secret}</code>
              <CopyButton text={secret} label="Copy" />
            </div>
          </div>
        </div>

        <div className="max-w-xs">
          <label htmlFor="mfa-confirm-code" className="block text-sm font-medium text-ink mb-1.5">Code from your app</label>
          <input
            id="mfa-confirm-code"
            type="text"
            inputMode="numeric"
            autoComplete="one-time-code"
            value={code}
            onChange={(e) => { setCode(e.target.value.replace(/\D/g, '').slice(0, 6)); setCodeError(null); }}
            placeholder="123456"
            className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-center text-lg tracking-[0.3em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30"
          />
        </div>
        {codeError && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5 max-w-xs">{codeError}</p>}

        <button
          type="button"
          onClick={() => confirmMutation.mutate()}
          disabled={confirmMutation.isPending || !/^\d{6}$/.test(code)}
          className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2.5 disabled:opacity-50"
        >
          {confirmMutation.isPending && <Loader2 size={14} className="animate-spin" />}
          Confirm and turn on
        </button>
      </div>
    );
  }

  // step === 'recovery-codes'. The only moment these are ever available in the clear (see
  // AdminMfaService.confirm's own doc comment) -- losing an authenticator app without one of
  // these is a real, permanent lockout, so RecoveryCodesStep requires an explicit acknowledgement
  // rather than a plain "Done" the admin might click past without reading anything above it.
  return <RecoveryCodesStep codes={recoveryCodes} onDone={onEnrolled} />;
}

function RecoveryCodesStep({ codes, onDone }: { codes: string[]; onDone: () => void }) {
  const [acknowledged, setAcknowledged] = useState(false);
  return (
    <div className="space-y-4">
      <p className="text-sm text-ink font-medium">Save your recovery codes</p>
      <p className="text-xs text-muted">
        Each code can be used once to sign in if you lose access to your authenticator app. Store
        them somewhere safe -- they won't be shown again.
      </p>
      <div className="grid grid-cols-2 gap-2 bg-bg border border-border rounded-lg p-4 font-mono text-sm text-ink max-w-sm">
        {codes.map((c) => <span key={c}>{c}</span>)}
      </div>
      <CopyButton text={codes.join('\n')} label="Copy all codes" />

      <label className="flex items-start gap-2 text-sm text-ink cursor-pointer pt-2 border-t border-border">
        <input
          type="checkbox"
          checked={acknowledged}
          onChange={(e) => setAcknowledged(e.target.checked)}
          className="mt-0.5"
        />
        I've saved these recovery codes somewhere safe.
      </label>
      <button
        type="button"
        onClick={onDone}
        disabled={!acknowledged}
        className="bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2.5 disabled:opacity-50"
      >
        Done
      </button>
    </div>
  );
}

function DisableFlow({ onDisabled, onCancel }: { onDisabled: () => void; onCancel: () => void }) {
  const notify = useNotify();
  const { data: me } = useQuery({ queryKey: ['admin-me-signin-method'], queryFn: () => userApi.get() });
  const [currentPassword, setCurrentPassword] = useState('');
  // A live TOTP code or a recovery code -- same proof-of-possession of the second factor that
  // turning MFA on required, so turning it off can't be done with just the account
  // password/Google credential (see AdminMfaService.disable's own doc comment on why: that alone
  // would mean a stolen live session plus the account password is enough to strip MFA).
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);

  const disableMutation = useMutation({
    mutationFn: (googleIdToken: string | null) =>
      adminMfaApi.disable(googleIdToken ? null : currentPassword, googleIdToken, code.trim()),
    onSuccess: () => {
      notify.success('Two-factor authentication disabled.');
      onDisabled();
    },
    onError: (err: any) =>
      setError(
        errorCodeOf(err) === AUTH_MFA_INVALID_CODE
          ? "That code didn't work. Check your authenticator app or use a recovery code."
          : (err?.response?.data?.message ?? 'Current credential could not be verified.')),
  });

  const codeField = (
    <div className="max-w-xs">
      <label htmlFor="mfa-disable-code" className="block text-sm font-medium text-ink mb-1.5">
        Authenticator code or recovery code
      </label>
      <input
        id="mfa-disable-code"
        type="text"
        autoComplete="one-time-code"
        value={code}
        onChange={(e) => { setCode(e.target.value); setError(null); }}
        placeholder="123456 or a recovery code"
        className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
      />
    </div>
  );

  return (
    <div className="space-y-3 border-t border-border pt-4">
      <p className="text-sm text-ink font-medium">Verify it's you to turn this off</p>
      {!me ? (
        <p className="text-sm text-muted">Loading…</p>
      ) : me.signInMethod === 'GOOGLE' ? (
        <div className="max-w-xs space-y-3">
          {codeField}
          {code.trim().length === 0 ? (
            <p className="text-xs text-muted">Enter a code above, then verify with Google.</p>
          ) : (
            <GoogleReauthPrompt
              onCredential={(idToken) => disableMutation.mutate(idToken)}
              onError={setError}
            />
          )}
        </div>
      ) : (
        <div className="max-w-xs space-y-3">
          <PasswordInput
            value={currentPassword}
            onChange={setCurrentPassword}
            placeholder="Current password"
            className="w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30"
          />
          {codeField}
          <button
            type="button"
            onClick={() => disableMutation.mutate(null)}
            disabled={disableMutation.isPending || currentPassword.length === 0 || code.trim().length === 0}
            className="inline-flex items-center gap-1.5 bg-danger hover:opacity-90 text-white text-sm font-semibold rounded-lg px-4 py-2.5 disabled:opacity-50"
          >
            {disableMutation.isPending && <Loader2 size={14} className="animate-spin" />}
            Disable two-factor authentication
          </button>
        </div>
      )}
      {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3.5 py-2.5 max-w-xs">{error}</p>}
      <button type="button" onClick={onCancel} className="text-muted hover:text-ink text-xs font-medium">
        Cancel
      </button>
    </div>
  );
}

function EnabledView({ onDisabled }: { onDisabled: () => void }) {
  const [disabling, setDisabling] = useState(false);
  if (disabling) return <DisableFlow onDisabled={onDisabled} onCancel={() => setDisabling(false)} />;
  return (
    <div className="flex items-center justify-between">
      <p className="text-sm text-muted">Two-factor authentication is turned on for your account.</p>
      <button
        type="button"
        onClick={() => setDisabling(true)}
        className="inline-flex items-center gap-1.5 text-sm font-medium text-danger border border-border rounded-lg px-3.5 py-2 hover:bg-bg"
      >
        <ShieldOff size={14} /> Disable
      </button>
    </div>
  );
}

/** "My security" -- deliberately rendered OUTSIDE Settings.tsx's SYSTEM_SETTINGS gate. This is
 *  "manage your own account's second factor," available to every signed-in admin regardless of
 *  which permissions they hold (AdminMfaController is gated on PORTAL_ADMIN alone, not a specific
 *  permission -- see its own doc comment) -- an admin without SYSTEM_SETTINGS would otherwise have
 *  no way to ever turn MFA on for their own account. */
export function MfaSection() {
  const queryClient = useQueryClient();
  const { data, isLoading, error } = useQuery({
    queryKey: ['admin-mfa-status'],
    queryFn: () => adminMfaApi.status(),
    retry: false,
  });

  function refetchStatus() {
    void queryClient.invalidateQueries({ queryKey: ['admin-mfa-status'] });
  }

  if (isLoading) {
    return (
      <Card>
        <CardHeader enabled={false} />
        <p className="text-sm text-muted">Loading…</p>
      </Card>
    );
  }

  // Off by default until an operator turns it on server-side (app.admin-mfa.enabled) -- a
  // deliberate, expected state, not an error. See AdminMfaService's own doc comment on why this
  // stays opt-in rather than force-enabled the moment this UI ships.
  if (error && errorCodeOf(error) === AUTH_MFA_NOT_AVAILABLE) {
    return (
      <Card>
        <CardHeader enabled={false} />
        <p className="text-sm text-muted">
          Two-factor authentication isn't turned on for this installation yet.
        </p>
      </Card>
    );
  }

  if (error || !data) {
    return (
      <Card>
        <CardHeader enabled={false} />
        <p className="text-sm text-danger">Could not load your two-factor authentication status. Please try again.</p>
      </Card>
    );
  }

  return (
    <Card>
      <CardHeader enabled={data.enabled} />
      {data.enabled
        ? <EnabledView onDisabled={refetchStatus} />
        : <EnrollFlow onEnrolled={refetchStatus} />}
    </Card>
  );
}
