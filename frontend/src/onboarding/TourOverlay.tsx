import { useEffect, useState } from 'react';
import { Button } from '../design-system';
import type { TourStep } from './tourSteps';

interface Props {
  steps: TourStep[];
  onFinish: () => void;
  onSkip: () => void;
}

export function TourOverlay({ steps, onFinish, onSkip }: Props) {
  const [index, setIndex] = useState(0);
  const [rect, setRect] = useState<DOMRect | null>(null);
  const step = steps[index];
  const isLast = index === steps.length - 1;

  useEffect(() => {
    const target = document.querySelector(step.targetSelector);
    setRect(target ? target.getBoundingClientRect() : null);
  }, [step.targetSelector]);

  function next() {
    if (isLast) {
      onFinish();
    } else {
      setIndex((i) => i + 1);
    }
  }

  function back() {
    setIndex((i) => Math.max(0, i - 1));
  }

  return (
    <div className="fixed inset-0 z-50" role="dialog" aria-label="Product tour">
      <div className="absolute inset-0 bg-black/60" />
      {rect && (
        <div
          className="absolute rounded-lg ring-4 ring-primary pointer-events-none"
          style={{ top: rect.top - 4, left: rect.left - 4, width: rect.width + 8, height: rect.height + 8 }}
        />
      )}
      <div
        className="absolute bg-card rounded-lg shadow-xl p-5 max-w-xs"
        style={rect ? { top: rect.bottom + 12, left: rect.left } : { top: '50%', left: '50%', transform: 'translate(-50%,-50%)' }}
      >
        <h3 className="font-bold text-ink mb-1">{step.title}</h3>
        <p className="text-sm text-muted mb-4">{step.body}</p>
        <div className="flex items-center justify-between">
          <button type="button" className="text-xs text-muted" onClick={onSkip}>Skip</button>
          <div className="flex gap-2">
            {index > 0 && <Button variant="secondary" size="sm" onClick={back}>Back</Button>}
            <Button variant="primary" size="sm" onClick={next}>{isLast ? 'Finish' : 'Next'}</Button>
          </div>
        </div>
      </div>
    </div>
  );
}
