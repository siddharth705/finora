import { Button } from '../design-system';

interface Props {
  onStart: () => void;
  onSkip: () => void;
}

export function WelcomeScreen({ onStart, onSkip }: Props) {
  return (
    <div className="flex flex-col items-center justify-center min-h-screen px-6 text-center">
      <h1 className="text-3xl font-bold text-ink mb-3">Welcome to Fynora 👋</h1>
      <p className="text-muted max-w-md mb-8">
        Take control of your finances in one place. Track spending, create budgets, monitor
        goals, and understand where your money goes with powerful insights.
      </p>
      <div className="flex gap-3">
        <Button variant="primary" onClick={onStart}>Start Setup</Button>
        <Button variant="secondary" onClick={onSkip}>Skip for Now</Button>
      </div>
    </div>
  );
}
