import { useNavigate } from 'react-router-dom';
import { Button } from '../design-system';
import { CHECKLIST_ITEMS } from './checklistItems';

interface Props {
  // Async in practice (the real prop, OnboardingFlow's finishOnboarding, awaits
  // onboardingApi.complete() before resolving) -- goThenNavigate below awaits it, so the type has
  // to say so, not just `() => void`.
  onDone: () => void | Promise<void>;
}

export function SuccessScreen({ onDone }: Props) {
  const navigate = useNavigate();

  // Bug fix: this used to fire onDone() (the real prop is async -- it awaits
  // onboardingApi.complete() before flipping onboardingCompleted) and navigate() in the same tick,
  // not sequenced. navigate() landed on the target route before onboardingCompleted actually
  // flipped true, so ProtectedRoute -- which gates on that flag, not on the URL -- still treated
  // the session as mid-onboarding and rendered OnboardingFlow's Success screen again at the new
  // URL. Awaiting onDone() first means the target page only ever renders once onboarding is
  // actually marked complete.
  async function goThenNavigate(path: string) {
    await onDone();
    void navigate(path);
  }

  return (
    <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
      <h1 className="text-3xl font-bold text-ink mb-3">You're Ready to Go 🚀</h1>
      <p className="text-muted max-w-md mb-6">
        Start by importing your first bank statement or connecting an account. The more data you
        add, the smarter Fynora becomes.
      </p>
      <div className="text-left mb-8">
        <p className="text-sm font-semibold text-ink mb-2">Next steps:</p>
        <ul className="space-y-1">
          {CHECKLIST_ITEMS.map((item) => (
            <li key={item.key} className="text-sm text-muted">☐ {item.label}</li>
          ))}
        </ul>
      </div>
      <div className="flex flex-col sm:flex-row gap-3">
        <Button variant="primary" onClick={() => goThenNavigate('/app/import')}>Import Statement</Button>
        <Button variant="secondary" onClick={() => goThenNavigate('/app/accounts')}>Connect Account</Button>
        <Button variant="secondary" onClick={onDone}>Go to Dashboard</Button>
      </div>
    </div>
  );
}
