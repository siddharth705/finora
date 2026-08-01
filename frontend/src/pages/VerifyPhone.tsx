import { useState } from 'react';
import type { FormEvent } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Sparkles, ShieldCheck } from 'lucide-react';
import { phoneApi } from '../api/endpoints';
import { useAuth } from '../context/AuthContext';

export default function VerifyPhone() {
  const navigate = useNavigate();
  const location = useLocation();
  const { setPhoneVerified } = useAuth();
  const [otp, setOtp] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [info, setInfo] = useState<string | null>(null);
  // Registration already issues the first OTP and returns it in the same response — seed it
  // here (via router state from Register.tsx) instead of leaving this null until the user
  // clicks "Resend" just to see a code for the first time. Falls back to null when this page
  // is reached any other way (e.g. a direct link, or a page refresh, which drops router state).
  const [devOtp, setDevOtp] = useState<string | null>(
    (location.state as { devOtp?: string | null } | null)?.devOtp ?? null
  );
  const [loading, setLoading] = useState(false);
  const [resending, setResending] = useState(false);

  async function handleVerify(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const res = await phoneApi.verifyOtp(otp);
      if (res.verified) {
        setPhoneVerified(true);
        navigate('/app');
      } else {
        setError(res.message);
      }
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Could not verify — try again.');
    } finally {
      setLoading(false);
    }
  }

  async function handleResend() {
    setResending(true);
    setError(null);
    setInfo(null);
    setDevOtp(null);
    try {
      const res = await phoneApi.sendOtp();
      setInfo(res.message);
      setDevOtp(res.devOtp);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Could not send a new code right now.');
    } finally {
      setResending(false);
    }
  }

  return (
    <div className="min-h-screen flex items-center justify-center bg-bg">
      <div className="bg-card rounded-xl2 p-8 w-full max-w-sm shadow-soft border border-border">
        <div className="flex items-center gap-2 mb-6">
          <span className="w-7 h-7 rounded-lg bg-gradient-to-br from-indigo-400 to-primary-dark flex items-center justify-center">
            <Sparkles size={14} className="text-white" strokeWidth={2.5} />
          </span>
          <span className="font-extrabold tracking-wide text-ink">FINORA</span>
        </div>

        <div className="flex items-center gap-2 mb-2">
          <ShieldCheck size={20} className="text-primary" />
          <h1 className="text-2xl font-bold text-ink">Verify your phone</h1>
        </div>
        <p className="text-sm text-muted mb-6">Enter the 6-digit code we sent to your mobile number.</p>

        {error && <p className="text-danger text-sm mb-4">{error}</p>}
        {info && <p className="text-success text-sm mb-2">{info}</p>}
        {devOtp && (
          <div className="bg-primary-light border border-primary/20 rounded-lg p-3 mb-4 text-xs">
            <p className="font-medium uppercase text-[10px] text-primary mb-1">No SMS provider configured yet — dev code:</p>
            <p className="font-mono text-base tracking-widest">{devOtp}</p>
          </div>
        )}

        <form onSubmit={handleVerify}>
          <label className="block text-xs font-medium text-muted mb-1">Verification code</label>
          <input
            value={otp}
            onChange={(e) => setOtp(e.target.value.replace(/\D/g, '').slice(0, 6))}
            required
            inputMode="numeric"
            placeholder="123456"
            className="bg-white text-gray-900 w-full border border-border rounded-lg px-3 py-2.5 mb-4 text-center text-lg tracking-[0.5em] font-mono focus:outline-none focus:ring-2 focus:ring-primary/30"
          />

          <button
            type="submit"
            disabled={loading || otp.length !== 6}
            className="w-full bg-primary hover:bg-primary-dark text-white rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
          >
            {loading ? 'Verifying…' : 'Verify'}
          </button>
        </form>

        <button
          onClick={handleResend}
          disabled={resending}
          className="w-full mt-3 text-xs text-primary font-medium text-center"
        >
          {resending ? 'Sending…' : "Didn't get a code? Resend"}
        </button>
      </div>
    </div>
  );
}
