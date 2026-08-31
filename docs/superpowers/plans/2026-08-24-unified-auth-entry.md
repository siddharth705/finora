# Unified Auth Entry (/login + /register → /auth) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Collapse `/login`, `/register`, and `/auth` into one screen at `/auth` that steps through identify → password/register → success in place, with no page navigation between steps. `/login` and `/register` become redirects for old bookmarks/links.

**Architecture:** Three focused step components (`IdentifyStep`, `PasswordStep`, `RegisterStep`) plus a shared `MarketingPanel`, all under `frontend/src/pages/auth-entry/`. `AuthEntry.tsx` becomes a thin orchestrator holding only `step`, `identifier`, and `banner` state, and renders whichever step is active. Every step still calls the real backend endpoint it calls today (`/auth/identify`, `/auth/login`, `/auth/register`, `/auth/google`, `/auth/apple`) — nothing about the merge changes what the backend requires.

**Tech Stack:** React + TypeScript, React Router, Vitest + Testing Library, existing `AuthContext`/`authApi` (unchanged).

**Spec:** `docs/superpowers/specs/2026-08-24-unified-auth-entry-design.md`

## Global Constraints

- Brand name in all new/moved copy is **"Fynora"** / **"FYNORA"** (rebrand landed on `main` 2026-08-24, before this work started) — not "Finora".
- `step` state and `location.state` are UI-only (never a security boundary) — every step transition that matters still round-trips through a real backend call. Do not add any client-side check that substitutes for a backend response.
- `location.state` carries only the identifier the user already typed and a UI-only banner string — never a provider, userId, or any other server-decided fact.
- A `409` from `/auth/register` stays authoritative regardless of what `/auth/identify` said earlier — no client-side "this can't happen" assumption.
- `/forgot-password` and `/reset-password` remain separate standalone routes — not touched by this plan except their `/login` cross-links (Task 7).
- No backend changes anywhere in this plan.
- TDD: write the failing test, verify it fails for the right reason, then implement.

---

## File Structure

Create:
- `frontend/src/pages/auth-entry/MarketingPanel.tsx` — shared feature-list panel (currently duplicated identically in `Login.tsx` and `Register.tsx`)
- `frontend/src/pages/auth-entry/MarketingPanel.test.tsx`
- `frontend/src/pages/auth-entry/IdentifyStep.tsx` — identifier form, calls `/auth/identify`
- `frontend/src/pages/auth-entry/IdentifyStep.test.tsx`
- `frontend/src/pages/auth-entry/PasswordStep.tsx` — password form + Google/Apple + reactivation, calls `/auth/login`
- `frontend/src/pages/auth-entry/PasswordStep.test.tsx`
- `frontend/src/pages/auth-entry/RegisterStep.tsx` — registration form + Google/Apple, calls `/auth/register`
- `frontend/src/pages/auth-entry/RegisterStep.test.tsx`

Rewrite:
- `frontend/src/pages/AuthEntry.tsx` — orchestrator (step machine, deep-link entry, "Not you?" reset, bfcache handling)
- `frontend/src/pages/AuthEntry.test.tsx` — full state-machine coverage

Modify (routing/redirect targets):
- `frontend/src/App.tsx`
- `frontend/src/components/ProtectedRoute.tsx`
- `frontend/src/components/Sidebar.tsx`
- `frontend/src/api/client.ts`
- `frontend/src/pages/ResetPassword.tsx`
- `frontend/src/pages/VerifyPhone.tsx`
- `frontend/src/pages/VerifyEmail.tsx`
- `frontend/src/pages/ForgotPassword.tsx`
- Their corresponding `.test.tsx` files where they assert on `/login`

Delete:
- `frontend/src/pages/Login.tsx`, `frontend/src/pages/Login.test.tsx`
- `frontend/src/pages/Register.tsx`, `frontend/src/pages/Register.test.tsx`

---

### Task 1: `MarketingPanel` shared component

**Files:**
- Create: `frontend/src/pages/auth-entry/MarketingPanel.tsx`
- Test: `frontend/src/pages/auth-entry/MarketingPanel.test.tsx`

**Interfaces:**
- Produces: `MarketingPanel({ badge, headline, description }: { badge: string; headline: ReactNode; description: string })` — a `<div className="hidden lg:block">` rendering the FYNORA logo link, badge pill, headline, description, the fixed `FEATURES` list, and the decorative icon row. Used by `PasswordStep` and `RegisterStep` (Tasks 3–4).

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/auth-entry/MarketingPanel.test.tsx
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { MarketingPanel } from './MarketingPanel';

describe('MarketingPanel', () => {
  it('renders the badge, headline, and description passed in as props', () => {
    render(
      <MemoryRouter>
        <MarketingPanel badge="Welcome back" headline="Pick up right where you left off" description="Sign in to see your finances." />
      </MemoryRouter>
    );
    expect(screen.getByText('Welcome back')).toBeInTheDocument();
    expect(screen.getByText('Pick up right where you left off')).toBeInTheDocument();
    expect(screen.getByText('Sign in to see your finances.')).toBeInTheDocument();
  });

  it('renders the fixed feature list regardless of props', () => {
    render(
      <MemoryRouter>
        <MarketingPanel badge="x" headline="x" description="x" />
      </MemoryRouter>
    );
    expect(screen.getByText('Secure & Private')).toBeInTheDocument();
    expect(screen.getByText('Auto Statement Import')).toBeInTheDocument();
    expect(screen.getByText('Investment Tracking')).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/auth-entry/MarketingPanel.test.tsx`
Expected: FAIL — `Failed to resolve import "./MarketingPanel"`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/pages/auth-entry/MarketingPanel.tsx
import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import {
  ShieldCheck, UploadCloud, TrendingUp, PiggyBank, Target, LineChart,
  Wallet, PieChart as PieChartIcon, BarChart3,
} from 'lucide-react';
import { BrandMark } from '../../components/BrandMark';

// Lifted verbatim from Login.tsx/Register.tsx, which had the identical array duplicated in
// both -- one copy now, shared by every step that shows the marketing panel.
const FEATURES = [
  { icon: ShieldCheck, iconBg: 'bg-blue-100', iconColor: 'text-blue-600', title: 'Secure & Private', desc: 'Your data is encrypted and bank-level secure.' },
  { icon: UploadCloud, iconBg: 'bg-green-100', iconColor: 'text-green-600', title: 'Auto Statement Import', desc: 'Import bank & credit card statements in seconds.' },
  { icon: TrendingUp, iconBg: 'bg-orange-100', iconColor: 'text-orange-600', title: 'AI Financial Insights', desc: 'AI-powered insights to help you save more.' },
  { icon: PiggyBank, iconBg: 'bg-purple-100', iconColor: 'text-purple-600', title: 'Budget Tracking', desc: 'Set budgets and stay effortlessly on track.' },
  { icon: Target, iconBg: 'bg-blue-100', iconColor: 'text-blue-600', title: 'Goal Management', desc: 'Plan and reach your financial goals faster.' },
  { icon: LineChart, iconBg: 'bg-teal-100', iconColor: 'text-teal-600', title: 'Investment Tracking', desc: 'Track your portfolio and net worth growth.' },
];

interface MarketingPanelProps {
  badge: string;
  headline: ReactNode;
  description: string;
}

export function MarketingPanel({ badge, headline, description }: MarketingPanelProps) {
  return (
    <div className="hidden lg:block">
      <Link to="/" className="flex items-center gap-2.5 mb-8 w-fit">
        <BrandMark size={36} variant="auto" className="rounded-lg" />
        <span className="font-extrabold tracking-wide text-ink text-xl">FYNORA</span>
      </Link>

      <span className="inline-block bg-primary-light text-primary text-xs font-medium px-3 py-1 rounded-full mb-4">
        {badge}
      </span>
      <h1 className="text-4xl font-bold text-ink leading-tight mb-4">{headline}</h1>
      <p className="text-muted text-base mb-8 max-w-md">{description}</p>

      <div className="space-y-5 mb-10">
        {FEATURES.map((f) => (
          <div key={f.title} className="flex items-start gap-3">
            <div className={`w-10 h-10 rounded-lg ${f.iconBg} flex items-center justify-center flex-shrink-0`}>
              <f.icon size={18} className={f.iconColor} />
            </div>
            <div>
              <p className="text-sm font-semibold text-ink">{f.title}</p>
              <p className="text-xs text-muted">{f.desc}</p>
            </div>
          </div>
        ))}
      </div>

      <div className="flex items-center gap-4 opacity-70">
        <div className="w-14 h-14 rounded-2xl bg-primary-light flex items-center justify-center">
          <Wallet size={22} className="text-primary" />
        </div>
        <div className="w-14 h-14 rounded-2xl bg-green-100 flex items-center justify-center -translate-y-2">
          <PieChartIcon size={22} className="text-green-600" />
        </div>
        <div className="w-14 h-14 rounded-2xl bg-orange-100 flex items-center justify-center">
          <BarChart3 size={22} className="text-orange-600" />
        </div>
      </div>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/auth-entry/MarketingPanel.test.tsx`
Expected: PASS, 2/2.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/auth-entry/MarketingPanel.tsx frontend/src/pages/auth-entry/MarketingPanel.test.tsx
git commit -m "feat(auth): extract shared MarketingPanel from Login/Register"
```

---

### Task 2: `IdentifyStep`

**Files:**
- Create: `frontend/src/pages/auth-entry/IdentifyStep.tsx`
- Test: `frontend/src/pages/auth-entry/IdentifyStep.test.tsx`

**Interfaces:**
- Consumes: `authApi.identify(identifier: string): Promise<{ nextAction: 'EXISTS' | 'CONTINUE' }>` (existing, `frontend/src/api/endpoints.ts`)
- Produces: `IdentifyStep({ onExists, onContinue }: { onExists: (identifier: string) => void; onContinue: (identifier: string, prefill: { email?: string; phoneNumber?: string }) => void })`. Owns its own `identifier`/`error`/`loading` state locally — nothing here needs to be shared with the orchestrator except through these two callbacks. Used by `AuthEntry.tsx` (Task 5).

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/auth-entry/IdentifyStep.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { IdentifyStep } from './IdentifyStep';
import { authApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  authApi: { identify: vi.fn() },
}));

beforeEach(() => vi.mocked(authApi.identify).mockReset());

describe('IdentifyStep', () => {
  it('calls onExists with the trimmed identifier when nextAction is EXISTS', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    const onExists = vi.fn();
    render(<IdentifyStep onExists={onExists} onContinue={vi.fn()} />);

    await userEvent.type(screen.getByLabelText('Email or mobile number'), '  jane@example.com  ');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(onExists).toHaveBeenCalledWith('jane@example.com'));
  });

  it('calls onContinue with an email prefill when nextAction is CONTINUE and the identifier looks like an email', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    const onContinue = vi.fn();
    render(<IdentifyStep onExists={vi.fn()} onContinue={onContinue} />);

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'new@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(onContinue).toHaveBeenCalledWith('new@example.com', { email: 'new@example.com' }));
  });

  it('calls onContinue with a phoneNumber prefill when the identifier looks like a phone number', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    const onContinue = vi.fn();
    render(<IdentifyStep onExists={vi.fn()} onContinue={onContinue} />);

    await userEvent.type(screen.getByLabelText('Email or mobile number'), '+919876500011');  // synthetic-ok: fake sequential example number
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    await waitFor(() => expect(onContinue).toHaveBeenCalledWith('+919876500011', { phoneNumber: '+919876500011' })); // synthetic-ok
  });

  it('shows an error and calls neither callback when the identifier is blank', async () => {
    const onExists = vi.fn();
    const onContinue = vi.fn();
    render(<IdentifyStep onExists={onExists} onContinue={onContinue} />);

    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    expect(await screen.findByText('Enter your email or mobile number.')).toBeInTheDocument();
    expect(onExists).not.toHaveBeenCalled();
    expect(onContinue).not.toHaveBeenCalled();
    expect(authApi.identify).not.toHaveBeenCalled();
  });

  it('shows the backend error message and does not call either callback when identify() rejects', async () => {
    vi.mocked(authApi.identify).mockRejectedValue({ response: { data: { message: 'Too many attempts, try again later.' } } });
    const onExists = vi.fn();
    const onContinue = vi.fn();
    render(<IdentifyStep onExists={onExists} onContinue={onContinue} />);

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    expect(await screen.findByText('Too many attempts, try again later.')).toBeInTheDocument();
    expect(onExists).not.toHaveBeenCalled();
    expect(onContinue).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/auth-entry/IdentifyStep.test.tsx`
Expected: FAIL — `Failed to resolve import "./IdentifyStep"`.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/pages/auth-entry/IdentifyStep.tsx
import { useState, type FormEvent } from 'react';
import { User } from 'lucide-react';
import { authApi } from '../../api/endpoints';

// Matches RegisterStep's own EMAIL_PATTERN -- used here only to decide which of Register's two
// fields (email vs mobile number) to prefill when nextAction is CONTINUE, not as a submission
// gate (the backend is the one source of truth for what counts as a valid identifier).
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface IdentifyStepProps {
  onExists: (identifier: string) => void;
  onContinue: (identifier: string, prefill: { email?: string; phoneNumber?: string }) => void;
}

export function IdentifyStep({ onExists, onContinue }: IdentifyStepProps) {
  const [identifier, setIdentifier] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const identifierValid = identifier.trim().length > 0;

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    if (!identifierValid) { setError('Enter your email or mobile number.'); return; }
    setLoading(true);
    try {
      const trimmed = identifier.trim();
      const { nextAction } = await authApi.identify(trimmed);
      if (nextAction === 'CONTINUE') {
        const isEmail = EMAIL_PATTERN.test(trimmed);
        onContinue(trimmed, isEmail ? { email: trimmed } : { phoneNumber: trimmed });
      } else {
        onExists(trimmed);
      }
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Something went wrong. Please try again.');
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <h2 className="text-2xl font-extrabold text-ink mb-1">Sign in or create an account</h2>
      <p className="text-sm text-muted mb-6">Enter your email or mobile number to continue</p>

      {error && <p className="text-danger text-sm mb-4">{error}</p>}

      <label htmlFor="auth-entry-identifier" className="block text-xs font-medium text-muted mb-1">Email or mobile number</label>
      <div className="relative mb-6">
        <User size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
        <input
          id="auth-entry-identifier"
          type="text"
          required
          autoComplete="username"
          value={identifier}
          onChange={(e) => setIdentifier(e.target.value)}
          placeholder="you@example.com or +91XXXXXXXXXX"
          className="w-full border border-border rounded-lg pl-9 pr-3 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
        />
      </div>

      <button
        type="submit"
        disabled={loading}
        className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold disabled:opacity-50"
      >
        {loading ? 'Continuing…' : 'Continue'}
      </button>
    </form>
  );
}
```

Note: `<label htmlFor>` text must read exactly `"Email or mobile number"` and the button `"Continue"` to match the tests above and `screen.getByLabelText`/`getByRole` queries.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/auth-entry/IdentifyStep.test.tsx`
Expected: PASS, 5/5.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/auth-entry/IdentifyStep.tsx frontend/src/pages/auth-entry/IdentifyStep.test.tsx
git commit -m "feat(auth): extract IdentifyStep component"
```

---

### Task 3: `PasswordStep`

**Files:**
- Create: `frontend/src/pages/auth-entry/PasswordStep.tsx`
- Test: `frontend/src/pages/auth-entry/PasswordStep.test.tsx`

**Interfaces:**
- Consumes: `useAuth().login(identifier, password): Promise<boolean>`, `.loginWithGoogle(idToken): Promise<boolean>`, `.loginWithApple(idToken, fullName): Promise<boolean>` (existing, `frontend/src/context/AuthContext.tsx`); `SESSION_ENDED_REASON_KEY` + `safeStorage` (existing); `ReactivateAccountPrompt`, `GoogleSignInButton`, `AppleSignInButton`, `PasswordInput` (existing, unmodified); `MarketingPanel` (Task 1)
- Produces: `PasswordStep({ identifier, banner, onSuccess, onNotYou }: { identifier: string; banner: string | null; onSuccess: (phoneVerified: boolean) => void; onNotYou: () => void })`. Used by `AuthEntry.tsx` (Task 5).

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/auth-entry/PasswordStep.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { PasswordStep } from './PasswordStep';
import { AuthProvider } from '../../context/AuthContext';
import { authApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  authApi: { login: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function renderStep(props: Partial<Parameters<typeof PasswordStep>[0]> = {}) {
  const onSuccess = vi.fn();
  const onNotYou = vi.fn();
  render(
    <MemoryRouter>
      <AuthProvider>
        <PasswordStep identifier="jane@example.com" banner={null} onSuccess={onSuccess} onNotYou={onNotYou} {...props} />
      </AuthProvider>
    </MemoryRouter>
  );
  return { onSuccess, onNotYou };
}

beforeEach(() => vi.mocked(authApi.login).mockReset());

describe('PasswordStep', () => {
  it('prefills the identifier field from props', () => {
    renderStep();
    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('jane@example.com');
  });

  it('shows the banner prop when present', () => {
    renderStep({ banner: 'Password updated successfully. Please sign in using your new password.' });
    expect(screen.getByText('Password updated successfully. Please sign in using your new password.')).toBeInTheDocument();
  });

  it('calls onSuccess with phoneVerified on a successful login', async () => {
    vi.mocked(authApi.login).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    } as any);
    const { onSuccess } = renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(true));
  });

  it('does NOT call onSuccess when login() rejects -- forcing the password step to render is not authentication', async () => {
    vi.mocked(authApi.login).mockRejectedValue({ response: { data: { message: 'Invalid credentials.' } } });
    const { onSuccess } = renderStep();

    await userEvent.type(screen.getByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('Invalid credentials.')).toBeInTheDocument();
    expect(onSuccess).not.toHaveBeenCalled();
  });

  it('calls onNotYou when "Not you?" is clicked', async () => {
    const { onNotYou } = renderStep();
    await userEvent.click(screen.getByRole('button', { name: /not you/i }));
    expect(onNotYou).toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/auth-entry/PasswordStep.test.tsx`
Expected: FAIL — `Failed to resolve import "./PasswordStep"`.

- [ ] **Step 3: Write the implementation**

Lift `Login.tsx`'s logic (`frontend/src/pages/Login.tsx:29-156` for state/handlers, `:207-296` for JSX inside the form), dropping the page-level wrapper `<div>`/marketing-panel/mobile-brand-header (the orchestrator now owns page layout), adding `identifier`/`banner`/`onSuccess`/`onNotYou` props, and adding the "Not you?" button:

```tsx
// frontend/src/pages/auth-entry/PasswordStep.tsx
import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, ArrowRight } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { PasswordInput } from '../../components/PasswordInput';
import { ReactivateAccountPrompt } from '../../components/ReactivateAccountPrompt';
import { GoogleSignInButton } from '../../components/GoogleSignInButton';
import { AppleSignInButton } from '../../components/AppleSignInButton';
import { SESSION_ENDED_REASON_KEY } from '../../api/client';
import { AUTH_ACCOUNT_DEACTIVATED } from '../../api/errorCodes';
import { safeStorage } from '../../lib/safeStorage';

interface PasswordStepProps {
  identifier: string;
  banner: string | null;
  onSuccess: (phoneVerified: boolean) => void;
  onNotYou: () => void;
}

export function PasswordStep({ identifier: initialIdentifier, banner, onSuccess, onNotYou }: PasswordStepProps) {
  const { login, loginWithGoogle, loginWithApple } = useAuth();
  // Editable, seeded from the orchestrator's identifier -- same UX as today's Login.tsx, which
  // lets the user correct a mistyped identifier without going all the way back to IDENTIFY.
  const [identifier, setIdentifier] = useState(initialIdentifier);
  const [password, setPassword] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [reactivationToken, setReactivationToken] = useState<string | null>(null);
  // Same one-shot-read-then-clear pattern as today's Login.tsx -- api/client.ts's forced-signout
  // stashes why the session ended because its window.location.href navigation unmounts React.
  const [sessionEndedReason] = useState<string | null>(() => {
    const reason = safeStorage.getItem(SESSION_ENDED_REASON_KEY);
    if (reason) safeStorage.removeItem(SESSION_ENDED_REASON_KEY);
    return reason;
  });

  const identifierValid = identifier.trim().length > 0;

  function afterAuthSuccess(phoneVerified: boolean) {
    onSuccess(phoneVerified);
  }

  function handleAuthError(err: any, fallbackMessage: string) {
    const token = err.response?.data?.errorCode === AUTH_ACCOUNT_DEACTIVATED
      ? err.response?.data?.details?.reactivationToken
      : null;
    if (token) {
      setReactivationToken(token);
    } else {
      setError(err.response?.data?.message ?? fallbackMessage);
    }
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!identifierValid) { setError('Enter your email or mobile number.'); return; }
    if (password.length === 0) { setError('Enter your password.'); return; }
    setLoading(true);
    try {
      afterAuthSuccess(await login(identifier.trim(), password));
    } catch (err: any) {
      handleAuthError(err, 'Login failed. Check your credentials.');
    } finally {
      setLoading(false);
    }
  }

  async function handleGoogleCredential(idToken: string) {
    setError(null);
    setLoading(true);
    try {
      afterAuthSuccess(await loginWithGoogle(idToken));
    } catch (err: any) {
      handleAuthError(err, 'Google sign-in failed.');
    } finally {
      setLoading(false);
    }
  }

  async function handleAppleCredential(idToken: string, fullName: string | null) {
    setError(null);
    setLoading(true);
    try {
      afterAuthSuccess(await loginWithApple(idToken, fullName));
    } catch (err: any) {
      handleAuthError(err, 'Apple sign-in failed.');
    } finally {
      setLoading(false);
    }
  }

  if (reactivationToken) {
    return (
      <ReactivateAccountPrompt
        token={reactivationToken}
        onCancel={() => setReactivationToken(null)}
        onReactivated={(phoneVerified) => onSuccess(phoneVerified)}
      />
    );
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <h2 className="text-2xl font-bold text-ink mb-1">Sign in</h2>
      <p className="text-sm text-muted mb-6">Enter your details to access your account</p>

      {banner && (
        <p className="text-success text-sm bg-success-bg rounded-lg px-3 py-2 mb-4">{banner}</p>
      )}
      {sessionEndedReason && !error && (
        <p role="status" className="text-warning text-sm bg-warning-bg rounded-lg px-3 py-2 mb-4">{sessionEndedReason}</p>
      )}
      {error && <p className="text-danger text-sm mb-4">{error}</p>}

      <label htmlFor="password-step-identifier" className="block text-xs font-medium text-muted mb-1">Email or mobile number</label>
      <input
        id="password-step-identifier"
        type="text"
        required
        autoComplete="username"
        value={identifier}
        onChange={(e) => setIdentifier(e.target.value)}
        placeholder="you@example.com or +91XXXXXXXXXX"
        className="w-full border border-border rounded-lg px-3 py-2.5 mb-4 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
      />

      <label htmlFor="password-step-password" className="block text-xs font-medium text-muted mb-1">Password</label>
      <PasswordInput
        id="password-step-password"
        value={password}
        onChange={setPassword}
        required
        autoComplete="current-password"
        className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 mb-2 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
      />
      <p className="text-right mb-6">
        <Link to="/forgot-password" className="text-xs text-primary font-medium">Forgot password?</Link>
      </p>

      <button
        type="submit"
        disabled={loading}
        className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold flex items-center justify-center gap-1.5 disabled:opacity-50"
      >
        {loading ? 'Signing in…' : 'Sign in'}
        {!loading && <ArrowRight size={15} />}
      </button>

      <div className="flex items-center gap-3 my-5">
        <div className="flex-1 h-px bg-border" />
        <span className="text-xs text-muted">OR</span>
        <div className="flex-1 h-px bg-border" />
      </div>

      <GoogleSignInButton text="signin_with" onCredential={handleGoogleCredential} onError={setError} />
      <div className="mt-3">
        <AppleSignInButton onCredential={handleAppleCredential} onError={setError} />
      </div>

      <div className="flex items-start gap-2.5 bg-primary-light rounded-lg p-3 mt-6">
        <ShieldCheck size={16} className="text-primary flex-shrink-0 mt-0.5" />
        <p className="text-xs text-ink">Your financial data is encrypted and securely protected.</p>
      </div>

      <p className="text-sm mt-4 text-center text-muted">
        <button type="button" onClick={onNotYou} className="text-primary font-medium">Not you?</button>
      </p>
    </form>
  );
}
```

Note: the field id changed from `login-identifier`/`login-password` to `password-step-identifier`/`password-step-password` since `id` must be unique per page and `RegisterStep` (Task 4) also has its own fields — the `<label>` text (queried by tests via `getByLabelText`) is unchanged.

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/auth-entry/PasswordStep.test.tsx`
Expected: PASS, 5/5.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/auth-entry/PasswordStep.tsx frontend/src/pages/auth-entry/PasswordStep.test.tsx
git commit -m "feat(auth): extract PasswordStep component with Not-you reset"
```

---

### Task 4: `RegisterStep`

**Files:**
- Create: `frontend/src/pages/auth-entry/RegisterStep.tsx`
- Test: `frontend/src/pages/auth-entry/RegisterStep.test.tsx`

**Interfaces:**
- Consumes: `useAuth().register(...)`, `.loginWithGoogle(...)`, `.loginWithApple(...)` (existing); `GoogleSignInButton`, `AppleSignInButton`, `PasswordInput` (existing)
- Produces: `RegisterStep({ prefill, referralCode, onSuccess, onAccountExists }: { prefill: { email?: string; phoneNumber?: string }; referralCode: string | undefined; onSuccess: (phoneVerified: boolean) => void; onAccountExists: (identifier: string) => void })`. `onAccountExists` fires on a `409`/`403` (email or phone already has an account) instead of today's `Link to="/login"` cross-page navigation. Used by `AuthEntry.tsx` (Task 5).

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/auth-entry/RegisterStep.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { RegisterStep } from './RegisterStep';
import { AuthProvider } from '../../context/AuthContext';
import { authApi } from '../../api/endpoints';

vi.mock('../../api/endpoints', () => ({
  authApi: { register: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function renderStep(props: Partial<Parameters<typeof RegisterStep>[0]> = {}) {
  const onSuccess = vi.fn();
  const onAccountExists = vi.fn();
  render(
    <MemoryRouter>
      <AuthProvider>
        <RegisterStep prefill={{}} referralCode={undefined} onSuccess={onSuccess} onAccountExists={onAccountExists} {...props} />
      </AuthProvider>
    </MemoryRouter>
  );
  return { onSuccess, onAccountExists };
}

async function fillValidForm() {
  await userEvent.type(screen.getByLabelText('Full name'), 'Jane Doe');
  await userEvent.type(screen.getByLabelText('Email'), 'jane@example.com');
  await userEvent.type(screen.getByLabelText('Mobile number'), '9876500011');  // synthetic-ok: fake sequential example number
  await userEvent.type(screen.getByLabelText('Password (min 8 characters)'), 'correct-password-1');
  await userEvent.type(screen.getByLabelText('Confirm password'), 'correct-password-1');
  await userEvent.click(screen.getByRole('checkbox'));
}

beforeEach(() => vi.mocked(authApi.register).mockReset());

describe('RegisterStep', () => {
  it('prefills the email field from the prefill prop', () => {
    renderStep({ prefill: { email: 'new@example.com' } });
    expect(screen.getByLabelText('Email')).toHaveValue('new@example.com');
  });

  it('calls onSuccess with phoneVerified on successful registration', async () => {
    vi.mocked(authApi.register).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane Doe', phoneVerified: false },
    } as any);
    const { onSuccess } = renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(onSuccess).toHaveBeenCalledWith(false));
  });

  it('calls onAccountExists with the identifier on a 409 instead of navigating away', async () => {
    vi.mocked(authApi.register).mockRejectedValue({ response: { status: 409, data: { message: 'Account already exists.' } } });
    const { onAccountExists, onSuccess } = renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(onAccountExists).toHaveBeenCalledWith('jane@example.com'));
    expect(onSuccess).not.toHaveBeenCalled();
    expect(screen.queryByRole('link', { name: /continue to login/i })).not.toBeInTheDocument();
  });

  it('does not call onSuccess or onAccountExists when register() fails for a non-409 reason', async () => {
    vi.mocked(authApi.register).mockRejectedValue({ response: { status: 400, data: { message: 'Bad input.' } } });
    const { onAccountExists, onSuccess } = renderStep();
    await fillValidForm();
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    expect(await screen.findByText('Bad input.')).toBeInTheDocument();
    expect(onAccountExists).not.toHaveBeenCalled();
    expect(onSuccess).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/auth-entry/RegisterStep.test.tsx`
Expected: FAIL — `Failed to resolve import "./RegisterStep"`.

- [ ] **Step 3: Write the implementation**

Lift `Register.tsx`'s helpers/state/handlers (`frontend/src/pages/Register.tsx:22-189`) and form JSX (`:269-434`, minus the marketing panel and the `showContinueLogin` link), replacing the 409/403 branch's link with `onAccountExists`:

```tsx
// frontend/src/pages/auth-entry/RegisterStep.tsx
import { useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import { ShieldCheck, User, Mail, CheckCircle2, ArrowRight } from 'lucide-react';
import { useAuth } from '../../context/AuthContext';
import { PasswordInput } from '../../components/PasswordInput';
import { GoogleSignInButton } from '../../components/GoogleSignInButton';
import { AppleSignInButton } from '../../components/AppleSignInButton';

function passwordStrength(pw: string): { score: number; label: string; color: string } {
  let score = 0;
  if (pw.length >= 8) score++;
  if (/[a-z]/.test(pw) && /[A-Z]/.test(pw)) score++;
  if (/[0-9]/.test(pw)) score++;
  if (/[^A-Za-z0-9]/.test(pw)) score++;
  const labels = ['Too short', 'Weak', 'Fair', 'Good', 'Strong'];
  const colors = ['bg-gray-300', 'bg-danger', 'bg-warning', 'bg-blue-500', 'bg-success'];
  return { score, label: labels[score], color: colors[score] };
}

function sanitizeLocalPhoneNumber(raw: string): string {
  return raw.replace(/[^0-9]/g, '').slice(0, 10);
}

function sanitizePastedPhoneNumber(raw: string): string {
  const digitsOnly = raw.replace(/[^0-9]/g, '');
  const local = digitsOnly.length > 10 && digitsOnly.startsWith('91') ? digitsOnly.slice(2) : digitsOnly;
  return local.slice(0, 10);
}

const PHONE_PATTERN = /^[6-9][0-9]{9}$/;
const FULL_NAME_PATTERN = /^[\p{L}][\p{L}\s.'-]{0,98}[\p{L}]$/u;
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;

interface RegisterStepProps {
  prefill: { email?: string; phoneNumber?: string };
  referralCode: string | undefined;
  onSuccess: (phoneVerified: boolean) => void;
  onAccountExists: (identifier: string) => void;
}

export function RegisterStep({ prefill, referralCode, onSuccess, onAccountExists }: RegisterStepProps) {
  const { register, loginWithGoogle, loginWithApple } = useAuth();
  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState(prefill.email ?? '');
  const [phoneNumber, setPhoneNumber] = useState(
    prefill.phoneNumber ? sanitizePastedPhoneNumber(prefill.phoneNumber) : '',
  );
  const [password, setPassword] = useState('');
  const [confirmPassword, setConfirmPassword] = useState('');
  const [agreedToTerms, setAgreedToTerms] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [touched, setTouched] = useState<Record<string, boolean>>({});

  const trimmedName = fullName.trim();
  const fullNameValid = trimmedName.length >= 2 && FULL_NAME_PATTERN.test(trimmedName);
  const emailValid = EMAIL_PATTERN.test(email.trim());
  const phoneValid = PHONE_PATTERN.test(phoneNumber);
  const passwordLongEnough = password.length >= 8;
  const strength = useMemo(() => passwordStrength(password), [password]);
  const passwordsMatch = confirmPassword.length > 0 && confirmPassword === password;

  const formValid =
    fullNameValid && emailValid && phoneValid && passwordLongEnough && passwordsMatch && agreedToTerms;

  function markTouched(field: string) {
    setTouched((t) => ({ ...t, [field]: true }));
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);
    setTouched({ fullName: true, email: true, phoneNumber: true, password: true, confirmPassword: true });

    if (!fullNameValid) { setError('Enter your full name using letters, spaces, hyphens, or apostrophes only.'); return; }
    if (!emailValid) { setError('Enter a valid email address.'); return; }
    if (!phoneValid) { setError('Enter a valid 10-digit mobile number.'); return; }
    if (!passwordLongEnough) { setError('Password must be at least 8 characters.'); return; }
    if (!passwordsMatch) { setError('Passwords do not match.'); return; }
    if (!agreedToTerms) { setError('Please agree to the Terms & Conditions to continue.'); return; }

    setLoading(true);
    try {
      const trimmedEmail = email.trim();
      const { phoneVerified } = await register(trimmedEmail, password, trimmedName, `+91${phoneNumber}`, referralCode);
      onSuccess(phoneVerified);
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Registration failed.');
      if (err.response?.status === 409) {
        onAccountExists(email.trim());
      }
    } finally {
      setLoading(false);
    }
  }

  async function handleGoogleCredential(idToken: string) {
    setError(null);
    setLoading(true);
    try {
      onSuccess(await loginWithGoogle(idToken));
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Google sign-in failed.');
      if (err.response?.status === 403) onAccountExists(email.trim());
    } finally {
      setLoading(false);
    }
  }

  async function handleAppleCredential(idToken: string, fullNameFromApple: string | null) {
    setError(null);
    setLoading(true);
    try {
      onSuccess(await loginWithApple(idToken, fullNameFromApple));
    } catch (err: any) {
      setError(err.response?.data?.message ?? 'Apple sign-in failed.');
      if (err.response?.status === 403) onAccountExists(email.trim());
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={handleSubmit} noValidate>
      <h2 className="text-2xl font-bold text-ink mb-1">Create your account</h2>
      <p className="text-sm text-muted mb-6">Start your journey towards financial clarity</p>

      {error && <p className="text-danger text-sm mb-4">{error}</p>}

      <label htmlFor="register-fullname" className="block text-xs font-medium text-muted mb-1">Full name</label>
      <div className="relative mb-1">
        <User size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
        <input
          id="register-fullname"
          value={fullName}
          onChange={(e) => setFullName(e.target.value)}
          onBlur={() => markTouched('fullName')}
          required
          placeholder="Enter your full name"
          className="w-full border border-border rounded-lg pl-9 pr-3 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
        />
      </div>
      <p className="text-[11px] mb-3 h-3.5">
        {touched.fullName && !fullNameValid && (
          <span className="text-danger">Letters, spaces, hyphens, and apostrophes only — no numbers or symbols.</span>
        )}
      </p>

      <label htmlFor="register-email" className="block text-xs font-medium text-muted mb-1">Email</label>
      <div className="relative mb-1">
        <Mail size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-muted" />
        <input
          id="register-email"
          type="email"
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          onBlur={() => markTouched('email')}
          required
          placeholder="you@example.com"
          className="w-full border border-border rounded-lg pl-9 pr-9 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
        />
        {emailValid && (
          <CheckCircle2 size={16} className="absolute right-3 top-1/2 -translate-y-1/2 text-success" />
        )}
      </div>
      <p className="text-[11px] mb-3 h-3.5">
        {touched.email && !emailValid && <span className="text-danger">Enter a valid email address.</span>}
      </p>

      <label htmlFor="register-phone" className="block text-xs font-medium text-muted mb-1">Mobile number</label>
      <div className="relative mb-1">
        <div className="absolute left-3 top-1/2 -translate-y-1/2 flex items-center gap-1.5 text-sm text-ink pointer-events-none select-none">
          <span aria-hidden="true">🇮🇳</span>
          <span>+91</span>
          <span className="w-px h-4 bg-border" />
        </div>
        <input
          id="register-phone"
          type="tel"
          inputMode="numeric"
          value={phoneNumber}
          onChange={(e) => setPhoneNumber(sanitizeLocalPhoneNumber(e.target.value))}
          onPaste={(e) => {
            e.preventDefault();
            setPhoneNumber(sanitizePastedPhoneNumber(e.clipboardData.getData('text')));
          }}
          onBlur={() => markTouched('phoneNumber')}
          required
          placeholder="XXXXXXXXXX"
          maxLength={10}
          title="10-digit mobile number"
          className="w-full border border-border rounded-lg pl-[4.75rem] pr-3 py-2.5 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
        />
      </div>
      <p className="text-[11px] mb-3 h-3.5">
        {touched.phoneNumber && !phoneValid && (
          <span className="text-danger">Enter a valid 10-digit mobile number (no leading 0-5).</span>
        )}
      </p>

      <label htmlFor="register-password" className="block text-xs font-medium text-muted mb-1">Password (min 8 characters)</label>
      <PasswordInput
        id="register-password"
        value={password}
        onChange={setPassword}
        onBlur={() => markTouched('password')}
        required
        minLength={8}
        maxLength={72}
        className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
      />
      {password.length > 0 && (
        <div className="mt-2 mb-1">
          <div className="flex gap-1 mb-1">
            {[0, 1, 2, 3].map((i) => (
              <div key={i} className={`h-1 flex-1 rounded-full ${i < strength.score ? strength.color : 'bg-gray-200'}`} />
            ))}
          </div>
          <p className="text-[11px] text-muted">{strength.label}</p>
        </div>
      )}
      <p className="text-[11px] mb-3 h-3.5">
        {touched.password && !passwordLongEnough && password.length === 0 && (
          <span className="text-danger">Password is required.</span>
        )}
      </p>

      <label htmlFor="register-confirm-password" className="block text-xs font-medium text-muted mb-1">Confirm password</label>
      <PasswordInput
        id="register-confirm-password"
        value={confirmPassword}
        onChange={setConfirmPassword}
        onBlur={() => markTouched('confirmPassword')}
        required
        className="w-full border border-border rounded-lg px-3 py-2.5 pr-10 text-sm bg-white text-gray-900 focus:outline-none focus:ring-2 focus:ring-primary/30"
      />
      {touched.confirmPassword && !passwordsMatch && (
        <p className="text-danger text-xs mt-1">Passwords don't match.</p>
      )}

      <label className="flex items-start gap-2 mt-4 mb-4 cursor-pointer">
        <input
          type="checkbox"
          checked={agreedToTerms}
          onChange={(e) => setAgreedToTerms(e.target.checked)}
          className="mt-0.5 rounded border-border"
        />
        <span className="text-xs text-muted">
          I agree to Fynora's <Link to="/terms" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Terms of Service</Link> and{' '}
          <Link to="/privacy" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Privacy Policy</Link>.
        </span>
      </label>

      <div className="flex items-start gap-2.5 bg-primary-light rounded-lg p-3 mb-6">
        <ShieldCheck size={16} className="text-primary flex-shrink-0 mt-0.5" />
        <p className="text-xs text-ink">
          Your financial data is encrypted and securely protected.{' '}
          <Link to="/privacy" target="_blank" rel="noopener noreferrer" className="text-primary font-medium">Read our Privacy Policy</Link>
        </p>
      </div>

      <button
        type="submit"
        disabled={loading || !formValid}
        className="w-full bg-primary hover:bg-primary-dark text-on-primary rounded-lg py-2.5 text-sm font-semibold flex items-center justify-center gap-1.5 disabled:opacity-50"
      >
        {loading ? 'Creating account…' : 'Create account'}
        {!loading && <ArrowRight size={15} />}
      </button>

      <div className="flex items-center gap-3 my-5">
        <div className="flex-1 h-px bg-border" />
        <span className="text-xs text-muted">OR</span>
        <div className="flex-1 h-px bg-border" />
      </div>

      <GoogleSignInButton text="signup_with" onCredential={handleGoogleCredential} onError={setError} />
      <div className="mt-3">
        <AppleSignInButton onCredential={handleAppleCredential} onError={setError} />
      </div>
    </form>
  );
}
```

Note: dropped `showContinueLogin`/the `Link to="/login"` entirely — `onAccountExists` replaces it. The 403 branch (Google/Apple hitting an account that already exists via a different method) now also calls `onAccountExists` instead of showing a dead link, which is a strict improvement over today's Register.tsx (today's 403 branch set `showContinueLogin` too, so the link showed — same information, now delivered as a step transition instead of a link).

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/auth-entry/RegisterStep.test.tsx`
Expected: PASS, 4/4.

- [ ] **Step 5: Commit**

```bash
git add frontend/src/pages/auth-entry/RegisterStep.tsx frontend/src/pages/auth-entry/RegisterStep.test.tsx
git commit -m "feat(auth): extract RegisterStep component with in-place 409 handling"
```

---

### Task 5: `AuthEntry` orchestrator

**Files:**
- Modify: `frontend/src/pages/AuthEntry.tsx` (full rewrite)
- Modify: `frontend/src/pages/AuthEntry.test.tsx` (full rewrite)

**Interfaces:**
- Consumes: `IdentifyStep`, `PasswordStep`, `RegisterStep` (Tasks 2-4), `MarketingPanel` (Task 1)
- Produces: default export `AuthEntry()`, the `/auth` route element. No new exports consumed elsewhere.

- [ ] **Step 1: Write the failing test**

```tsx
// frontend/src/pages/AuthEntry.test.tsx
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes, useLocation, useNavigate } from 'react-router-dom';
import { useEffect } from 'react';
import AuthEntry from './AuthEntry';
import { AuthProvider } from '../context/AuthContext';
import { authApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  authApi: { identify: vi.fn(), login: vi.fn(), register: vi.fn(), google: vi.fn(), apple: vi.fn(), logout: vi.fn() },
  userApi: { get: vi.fn(), update: vi.fn() },
}));

function AppStub() {
  return <div>App home</div>;
}

// Lets a test navigate to /auth WITH router state, the same way ResetPassword.tsx's deep link does.
function NavigateWithState({ state }: { state: unknown }) {
  const navigate = useNavigate();
  useEffect(() => { navigate('/auth', { state, replace: true }); }, [navigate]);
  return null;
}

function renderAt(initialEntries: any[] = ['/auth']) {
  render(
    <MemoryRouter initialEntries={initialEntries}>
      <AuthProvider>
        <Routes>
          <Route path="/auth" element={<AuthEntry />} />
          <Route path="/app" element={<AppStub />} />
          <Route path="/verify-phone" element={<div>Verify phone</div>} />
          <Route path="/start" element={<NavigateWithState state={initialEntries[0]?.state} />} />
        </Routes>
      </AuthProvider>
    </MemoryRouter>
  );
}

beforeEach(() => {
  vi.mocked(authApi.identify).mockReset();
  vi.mocked(authApi.login).mockReset();
  vi.mocked(authApi.register).mockReset();
});

describe('AuthEntry orchestrator', () => {
  it('starts on the identify step by default', () => {
    renderAt();
    expect(screen.getByLabelText('Email or mobile number')).toBeInTheDocument();
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
  });

  it('identify -> EXISTS -> password -> successful login -> /app, with no page navigation', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    vi.mocked(authApi.login).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'jane@example.com', fullName: 'Jane', phoneVerified: true },
    } as any);
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    const passwordField = await screen.findByLabelText('Password');
    await userEvent.type(passwordField, 'correct-password-1');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => expect(screen.getByText('App home')).toBeInTheDocument());
  });

  it('identify -> CONTINUE -> register -> successful registration -> /verify-phone', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    vi.mocked(authApi.register).mockResolvedValue({
      data: { token: 't', refreshToken: 'r', email: 'new@example.com', fullName: 'Jane Doe', phoneVerified: false },
    } as any);
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'new@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));

    expect(await screen.findByLabelText('Email')).toHaveValue('new@example.com');
    await userEvent.type(screen.getByLabelText('Full name'), 'Jane Doe');
    await userEvent.type(screen.getByLabelText('Mobile number'), '9876500011');  // synthetic-ok: fake sequential example number
    await userEvent.type(screen.getByLabelText('Password (min 8 characters)'), 'correct-password-1');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    await waitFor(() => expect(screen.getByText('Verify phone')).toBeInTheDocument());
  });

  it('register 409 switches to the password step with the identifier and a banner, not a page navigation', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'CONTINUE' });
    vi.mocked(authApi.register).mockRejectedValue({ response: { status: 409, data: { message: 'Account already exists.' } } });
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'taken@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    await userEvent.type(await screen.findByLabelText('Full name'), 'Jane Doe');
    await userEvent.type(screen.getByLabelText('Mobile number'), '9876500011');  // synthetic-ok: fake sequential example number
    await userEvent.type(screen.getByLabelText('Password (min 8 characters)'), 'correct-password-1');
    await userEvent.type(screen.getByLabelText('Confirm password'), 'correct-password-1');
    await userEvent.click(screen.getByRole('checkbox'));
    await userEvent.click(screen.getByRole('button', { name: /create account/i }));

    const passwordField = await screen.findByLabelText('Password');
    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('taken@example.com');
    expect(passwordField).toBeInTheDocument();
  });

  it('deep-link entry with skipToPassword starts directly on the password step, prefilled with a banner', async () => {
    renderAt([{ pathname: '/auth', state: { identifier: 'jane@example.com', banner: 'Password reset successfully. Please sign in using your new password.', skipToPassword: true } }]);

    expect(screen.queryByRole('button', { name: /^continue$/i })).not.toBeInTheDocument();
    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('jane@example.com');
    expect(screen.getByText('Password reset successfully. Please sign in using your new password.')).toBeInTheDocument();
  });

  it('"Not you?" returns to the identify step with a blank identifier and clears the password field', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    await userEvent.type(await screen.findByLabelText('Password'), 'some-password');
    await userEvent.click(screen.getByRole('button', { name: /not you/i }));

    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('');
    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();

    // Going through identify again proves the password field is genuinely gone, not just hidden.
    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    expect(await screen.findByLabelText('Password')).toHaveValue('');
  });

  it('a bfcache restore (pageshow with persisted=true) resets to identify even mid-flow', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    await screen.findByLabelText('Password');

    fireEvent(window, new Event('pageshow', { bubbles: true }) as any);
    Object.defineProperty(window.event ?? {}, 'persisted', { value: true, configurable: true });
    // jsdom's Event doesn't set `persisted` via the constructor -- dispatch a PageTransitionEvent-shaped
    // object directly so the component's own `event.persisted` check sees it.
    const pageShowEvent = new Event('pageshow');
    Object.defineProperty(pageShowEvent, 'persisted', { value: true });
    window.dispatchEvent(pageShowEvent);

    await waitFor(() => expect(screen.queryByLabelText('Password')).not.toBeInTheDocument());
    expect(screen.getByLabelText('Email or mobile number')).toHaveValue('');
  });

  it('regression: rendering the password step alone never authenticates -- onSuccess only fires after a real login() resolution', async () => {
    vi.mocked(authApi.identify).mockResolvedValue({ nextAction: 'EXISTS' });
    vi.mocked(authApi.login).mockRejectedValue({ response: { data: { message: 'Invalid credentials.' } } });
    renderAt();

    await userEvent.type(screen.getByLabelText('Email or mobile number'), 'jane@example.com');
    await userEvent.click(screen.getByRole('button', { name: /continue/i }));
    await userEvent.type(await screen.findByLabelText('Password'), 'wrong-password');
    await userEvent.click(screen.getByRole('button', { name: /sign in/i }));

    expect(await screen.findByText('Invalid credentials.')).toBeInTheDocument();
    expect(screen.queryByText('App home')).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/pages/AuthEntry.test.tsx`
Expected: FAIL — the old `AuthEntry.tsx` has no step machine yet, so most assertions (e.g. `screen.findByLabelText('Password')` appearing on the same render) fail or time out.

- [ ] **Step 3: Write the implementation**

```tsx
// frontend/src/pages/AuthEntry.tsx
import { useEffect, useState } from 'react';
import { Link, useLocation, useSearchParams, useNavigate } from 'react-router-dom';
import { ShieldCheck } from 'lucide-react';
import { BrandMark } from '../components/BrandMark';
import { MarketingPanel } from './auth-entry/MarketingPanel';
import { IdentifyStep } from './auth-entry/IdentifyStep';
import { PasswordStep } from './auth-entry/PasswordStep';
import { RegisterStep } from './auth-entry/RegisterStep';

type Step = 'identify' | 'password' | 'register';

interface DeepLinkState {
  identifier?: string;
  banner?: string;
  skipToPassword?: boolean;
}

/**
 * Unified authentication entry page (auth/security review §2.2 / Phase 3, collapsed into a
 * single screen 2026-08-24 -- see docs/superpowers/specs/2026-08-24-unified-auth-entry-design.md).
 * /auth is the only route a user navigates to; /login and /register are redirects (App.tsx) kept
 * only for old bookmarks/links. Every transition below is UI-only -- see the spec's Security
 * boundaries section: `step` and `identifier` never substitute for a real backend call. Each step
 * component still posts to the real endpoint it always did (/auth/identify, /auth/login,
 * /auth/register, /auth/google, /auth/apple) and only advances on that call's own success.
 */
export default function AuthEntry() {
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  // D-28 PR4-C, unchanged from Register.tsx: a referral link's `?ref=` is only meaningful for the
  // signup this page load represents.
  const referralCode = searchParams.get('ref') ?? undefined;

  const deepLink = location.state as DeepLinkState | null;
  const [step, setStep] = useState<Step>(deepLink?.skipToPassword ? 'password' : 'identify');
  const [identifier, setIdentifier] = useState(deepLink?.identifier ?? '');
  const [banner, setBanner] = useState<string | null>(deepLink?.banner ?? null);
  const [registerPrefill, setRegisterPrefill] = useState<{ email?: string; phoneNumber?: string }>({});

  // Reset to a safe default on a back/forward-cache restore -- some browsers restore this exact
  // live component instance (not a fresh mount) when the user hits Forward after Back, which would
  // otherwise silently show whatever step/password/reactivation state was on screen when they left.
  // A genuine reload is unaffected: it always remounts from scratch, so `useState`'s initial values
  // (including the deep-link case above, which browsers persist across a reload of that same
  // history entry) already do the right thing without this handler.
  useEffect(() => {
    function handlePageShow(event: Event) {
      if ((event as PageTransitionEvent).persisted) {
        setStep('identify');
        setIdentifier('');
        setBanner(null);
        setRegisterPrefill({});
      }
    }
    window.addEventListener('pageshow', handlePageShow);
    return () => window.removeEventListener('pageshow', handlePageShow);
  }, []);

  function afterAuthSuccess(phoneVerified: boolean) {
    void navigate(phoneVerified ? '/app' : '/verify-phone', { state: phoneVerified ? undefined : { fromLogin: true } });
  }

  function handleExists(existingIdentifier: string) {
    setIdentifier(existingIdentifier);
    setBanner(null);
    setStep('password');
  }

  function handleContinue(newIdentifier: string, prefill: { email?: string; phoneNumber?: string }) {
    setIdentifier(newIdentifier);
    setRegisterPrefill(prefill);
    setStep('register');
  }

  // 409 from RegisterStep -- the DB unique constraint is the real authority here, not the earlier
  // /auth/identify CONTINUE (see the spec's "registration race" note). Replaces today's Register.tsx
  // "Continue to login" link with an in-place step switch.
  function handleAccountExists(existingIdentifier: string) {
    setIdentifier(existingIdentifier);
    setBanner('This email or mobile number already has an account — sign in below.');
    setStep('password');
  }

  // Clears everything transient, not just the step -- identifier, and (by unmounting PasswordStep/
  // RegisterStep) their own local password/confirmPassword/error/reactivation-token/OAuth-in-flight
  // state along with them. Matters on shared computers.
  function handleNotYou() {
    setStep('identify');
    setIdentifier('');
    setBanner(null);
    setRegisterPrefill({});
  }

  const marketingCopy = step === 'password'
    ? { badge: 'Welcome back', headline: <>Pick up right where you <span className="text-primary">left off</span></>, description: 'Sign in to see your latest transactions, budgets, goals and AI-powered insights — all in one place.' }
    : { badge: 'Your finances, finally in one place', headline: <>Take control of your money with <span className="text-primary">Fynora</span></>, description: 'Import statements, track spending, set budgets and get AI-powered insights to build a better financial future.' };

  return (
    <div className="min-h-screen bg-bg flex flex-col items-center justify-center p-4 lg:p-8 gap-6">
      <div className="w-full max-w-6xl grid lg:grid-cols-2 gap-10 lg:gap-16 items-center">
        <MarketingPanel {...marketingCopy} />

        <div className="bg-card rounded-xl2 p-8 w-full shadow-soft border border-border">
          <div className="flex items-center gap-2 mb-6 lg:hidden">
            <Link to="/" className="flex items-center gap-2 w-fit">
              <BrandMark size={28} variant="auto" className="rounded-lg" />
              <span className="font-extrabold tracking-wide text-ink">FYNORA</span>
            </Link>
          </div>

          {step === 'identify' && (
            <IdentifyStep onExists={handleExists} onContinue={handleContinue} />
          )}
          {step === 'password' && (
            <PasswordStep identifier={identifier} banner={banner} onSuccess={afterAuthSuccess} onNotYou={handleNotYou} />
          )}
          {step === 'register' && (
            <RegisterStep prefill={registerPrefill} referralCode={referralCode} onSuccess={afterAuthSuccess} onAccountExists={handleAccountExists} />
          )}
        </div>
      </div>

      <p className="text-xs text-muted flex items-center gap-2">
        <ShieldCheck size={13} /> Bank-grade encryption. Your data is never sold.
      </p>
    </div>
  );
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd frontend && npx vitest run src/pages/AuthEntry.test.tsx`
Expected: PASS, 8/8. If the `pageshow` test is flaky in jsdom (constructing a `persisted: true` event varies by jsdom version), simplify it to directly invoke the registered listener: capture the handler via `vi.spyOn(window, 'addEventListener')` in that one test and call it with `{ persisted: true }` directly, instead of dispatching a real event.

- [ ] **Step 5: Run the full frontend suite and typecheck**

Run: `cd frontend && npx vitest run && npx tsc -b && npx eslint src/pages/AuthEntry.tsx src/pages/auth-entry`
Expected: all pass. `Login.test.tsx`/`Register.test.tsx` still exist and still pass at this point (deleted in Task 6) — `Login.tsx`/`Register.tsx` themselves are untouched by this task.

- [ ] **Step 6: Commit**

```bash
git add frontend/src/pages/AuthEntry.tsx frontend/src/pages/AuthEntry.test.tsx
git commit -m "feat(auth): AuthEntry becomes the identify/password/register orchestrator"
```

---

### Task 6: Routing — `/login`/`/register` become redirects, delete the old pages

**Files:**
- Modify: `frontend/src/App.tsx:129-130`
- Delete: `frontend/src/pages/Login.tsx`, `frontend/src/pages/Login.test.tsx`
- Delete: `frontend/src/pages/Register.tsx`, `frontend/src/pages/Register.test.tsx`

**Interfaces:**
- Consumes: `AuthEntry` (Task 5)
- Produces: nothing new — this task only removes the old routes/files.

- [ ] **Step 1: Write the failing test**

`App.test.tsx` renders `<App />` directly (it owns its own router internally) and drives
navigation via `window.history.pushState` before rendering — follow that exact existing pattern,
not a `MemoryRouter` wrapper. Add alongside the existing `describe('App routing — unmatched
paths', ...)` block:

```tsx
describe('App routing — /login and /register redirect to /auth', () => {
  beforeEach(() => {
    window.history.pushState({}, '', '/');
  });

  it.each(['/login', '/register'])('redirects %s to /auth', async (path) => {
    window.history.pushState({}, '', path);
    render(<App />);
    await waitFor(() => expect(window.location.pathname).toBe('/auth'));
  });
});
```

Note: `ProtectedRoute.tsx`'s own redirect target is updated separately in Task 7 (it's an
internal call site, not a `/login`/`/register` route) — its test still asserts `/login` until
then, which is correct and expected at this point in the sequence.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd frontend && npx vitest run src/App.test.tsx`
Expected: FAIL — `/login` and `/register` still render the old `Login`/`Register` pages, not the identify form.

- [ ] **Step 3: Update `App.tsx`**

```tsx
// frontend/src/App.tsx -- replace lines 129-130
          <Route path="/login" element={<Navigate to="/auth" replace />} />
          <Route path="/register" element={<Navigate to="/auth" replace />} />
```

Remove the now-unused `import Login from './pages/Login';` and `import Register from './pages/Register';` lines, and confirm `Navigate` is already imported from `react-router-dom` in this file (it is, used by `ProtectedRoute` elsewhere — check the existing import line and add `Navigate` to it if not already present).

- [ ] **Step 4: Delete the old pages and their tests**

```bash
git rm frontend/src/pages/Login.tsx frontend/src/pages/Login.test.tsx
git rm frontend/src/pages/Register.tsx frontend/src/pages/Register.test.tsx
```

- [ ] **Step 5: Run the full frontend suite and typecheck**

Run: `cd frontend && npx vitest run && npx tsc -b`
Expected: all pass, no dangling imports of the deleted files (grep to confirm: `grep -rn "pages/Login'\|pages/Register'" src` should return nothing except the two `Navigate` lines' comments, if any).

- [ ] **Step 6: Commit**

```bash
git add frontend/src/App.tsx frontend/src/App.test.tsx
git commit -m "feat(auth): /login and /register redirect to /auth, remove old pages"
```

---

### Task 7: Update internal navigation call sites

**Files:**
- Modify: `frontend/src/components/ProtectedRoute.tsx:24`
- Modify: `frontend/src/components/Sidebar.tsx:58`
- Modify: `frontend/src/api/client.ts:184`
- Modify: `frontend/src/pages/ResetPassword.tsx:147-149`
- Modify: `frontend/src/pages/VerifyPhone.tsx:180`
- Modify: `frontend/src/pages/VerifyEmail.tsx:51,61`
- Modify: `frontend/src/pages/ForgotPassword.tsx:61,93`
- Modify: their existing `.test.tsx` files wherever they assert on `/login`

**Interfaces:**
- Consumes: nothing new
- Produces: nothing new — avoids a redundant `/login` → `/auth` client-side redirect hop for every in-app navigation.

- [ ] **Step 1: Update failing assertions first (TDD, one file at a time)**

For each file below, find its existing test(s) asserting `to="/login"` or `navigate('/login')` and change the expected target to `/auth` (with the equivalent state shape where noted). Run each test file to confirm it now fails against the still-unmodified source, then fix the source, then re-run to confirm green, before moving to the next file. Example for `ProtectedRoute.test.tsx`:

```tsx
// change: expect(...).toHaveAttribute('to', '/login')  (or equivalent Navigate assertion)
// to:     expect(...).toHaveAttribute('to', '/auth')
```

- [ ] **Step 2: `ProtectedRoute.tsx`**

First update its test — `renderProtected`'s helper stubs `/login` as a route directly (line 26)
and `'redirects to /login when there is no token'` (lines 38-45) asserts on it:

```tsx
// frontend/src/components/ProtectedRoute.test.tsx:26 -- change
<Route path="/login" element={<div>Login page</div>} />
// to
<Route path="/auth" element={<div>Auth page</div>} />
```

```tsx
// frontend/src/components/ProtectedRoute.test.tsx:38-45 -- change
it('redirects to /login when there is no token', () => {
  vi.mocked(useAuth).mockReturnValue({ token: null, bootstrapping: false, phoneVerified: false } as ReturnType<typeof useAuth>);
  renderProtected();
  expect(screen.getByText('Login page')).toBeInTheDocument();
  expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
});
// to
it('redirects to /auth when there is no token', () => {
  vi.mocked(useAuth).mockReturnValue({ token: null, bootstrapping: false, phoneVerified: false } as ReturnType<typeof useAuth>);
  renderProtected();
  expect(screen.getByText('Auth page')).toBeInTheDocument();
  expect(screen.queryByText('Protected content')).not.toBeInTheDocument();
});
```

Run: `cd frontend && npx vitest run src/components/ProtectedRoute.test.tsx` — expect FAIL (source still says `/login`).

Then update the source:

```tsx
// frontend/src/components/ProtectedRoute.tsx:24 -- change
if (!token) return <Navigate to="/login" replace />;
// to
if (!token) return <Navigate to="/auth" replace />;
```

Run: `cd frontend && npx vitest run src/components/ProtectedRoute.test.tsx` — expect PASS.

- [ ] **Step 3: `Sidebar.tsx`**

```tsx
// frontend/src/components/Sidebar.tsx:58 -- change
void navigate('/login');
// to
void navigate('/auth');
```

Run: `cd frontend && npx vitest run src/components/Sidebar.test.tsx` — expect PASS after the change.

- [ ] **Step 4: `api/client.ts`**

```tsx
// frontend/src/api/client.ts:184 -- change
window.location.href = '/login';
// to
window.location.href = '/auth';
```

This is a full page load (unaffected by React Router), so `AuthEntry`'s deep-link `location.state` reading doesn't apply here — `SESSION_ENDED_REASON_KEY` in `safeStorage` (already read by `PasswordStep`, Task 3) is what carries the reason across this hard navigation, exactly as it did into `Login.tsx` before. No further change needed here beyond the URL string.

Run: `cd frontend && npx vitest run src/api/client.test.ts` — expect PASS after the change.

- [ ] **Step 5: `ResetPassword.tsx`**

```tsx
// frontend/src/pages/ResetPassword.tsx:147-149 -- change
void navigate('/login', {
  state: { message: 'Password reset successfully. Please sign in using your new password.' },
});
// to
void navigate('/auth', {
  state: {
    identifier: email, // the email this reset was for -- ResetPassword.tsx already has it in scope from the token lookup; use that existing variable, not a new field
    banner: 'Password reset successfully. Please sign in using your new password.',
    skipToPassword: true,
  },
});
```

Read the surrounding function first to confirm the exact in-scope variable name holding the account's email at this point (it comes from the token/reset flow, not user input) and use that name — do not introduce a new state field for it.

Run: `cd frontend && npx vitest run src/pages/ResetPassword.test.tsx` — expect PASS after the change (update any test asserting the old `navigate('/login', {state: {message: ...}})` call to assert the new shape).

- [ ] **Step 6: `VerifyPhone.tsx`, `VerifyEmail.tsx`, `ForgotPassword.tsx`**

```tsx
// frontend/src/pages/VerifyPhone.tsx:180 -- change
void navigate('/login');
// to
void navigate('/auth');
```

```tsx
// frontend/src/pages/VerifyEmail.tsx:51 and :61 -- change both
to="/login"
// to
to="/auth"
```

```tsx
// frontend/src/pages/ForgotPassword.tsx:61 and :93 -- change both
<Link to="/login" ...>Back to sign in</Link>
// to
<Link to="/auth" ...>Back to sign in</Link>
```

Run: `cd frontend && npx vitest run src/pages/VerifyPhone.test.tsx src/pages/VerifyEmail.test.tsx src/pages/ForgotPassword.test.tsx` — expect PASS after each change.

- [ ] **Step 7: Full sweep for anything missed**

Run: `cd frontend && grep -rn "'/login'\|\"/login\"" src --include="*.tsx" --include="*.ts" | grep -v ".test."`
Expected: no output (every real, non-test reference to `/login` has been updated). If anything remains, update it the same way.

- [ ] **Step 8: Run the full frontend suite, typecheck, and lint**

Run: `cd frontend && npx vitest run && npx tsc -b && npx eslint src`
Expected: all green.

- [ ] **Step 9: Commit**

```bash
git add frontend/src/components/ProtectedRoute.tsx frontend/src/components/ProtectedRoute.test.tsx \
        frontend/src/components/Sidebar.tsx frontend/src/components/Sidebar.test.tsx \
        frontend/src/api/client.ts frontend/src/api/client.test.ts \
        frontend/src/pages/ResetPassword.tsx frontend/src/pages/ResetPassword.test.tsx \
        frontend/src/pages/VerifyPhone.tsx frontend/src/pages/VerifyPhone.test.tsx \
        frontend/src/pages/VerifyEmail.tsx frontend/src/pages/VerifyEmail.test.tsx \
        frontend/src/pages/ForgotPassword.tsx frontend/src/pages/ForgotPassword.test.tsx
git commit -m "feat(auth): point internal navigation at /auth directly, skip the /login redirect hop"
```

---

## Final Verification (after Task 7)

- [ ] `cd frontend && npx vitest run` — full suite green
- [ ] `cd frontend && npx tsc -b` — clean
- [ ] `cd frontend && npx eslint src` — clean
- [ ] `grep -rn "pages/Login'\|pages/Register'\|'/login'\|\"/login\"" frontend/src --include="*.tsx" --include="*.ts" | grep -v ".test."` — empty
- [ ] Manual/visual check (browser preview, if available in the working environment): `/auth` → type an existing email → password step appears in place, no URL change; go back, type a new email → register step appears in place; visit `/login` directly → redirected to `/auth`.
- [ ] Update `docs/superpowers/specs/2026-08-24-unified-auth-entry-design.md`'s `Status:` line from "approved, pending implementation plan" to "implemented".
