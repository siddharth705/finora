import { useEffect, useRef, useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';

/** How long a revealed number stays visible before hiding itself again. Short enough that walking
 *  away from a screen doesn't leave it showing, long enough to read a number off it and compare. */
const AUTO_REMASK_MS = 8000;

/**
 * An account number, hidden behind a placeholder until the reader asks to see it.
 *
 * <b>What "revealing" actually shows.</b> Not a full account number — Finora never has one.
 * {@code Account.accountNumberMasked} is documented as never holding an unmasked value: real bank
 * exports hand over an already-partially-masked string like "XXXXXX4587", and that is all that has
 * ever existed to store. So this toggle swaps a generic "•••• ••••" for the bank's own masked form.
 *
 * That makes this a <b>presentation control, not a security boundary</b>, and it is worth being
 * precise about the difference: it exists so a statement's last four digits are not sitting on
 * screen by default during a screen share or over someone's shoulder. It protects against a glance,
 * not against an attacker — anyone who can read the page can click the button.
 *
 * <b>Why a component rather than a third copy.</b> Setup.tsx had this behaviour and Import.tsx had
 * neither half of it: the detected-account field rendered the masked number outright with no
 * toggle, while the account summary line showed a hard-coded "•••• ••••" with no way to reveal it
 * at all. Two screens showing the same field three different ways is how the next screen ends up
 * showing it a fourth. The auto-remask timer in particular is the kind of detail a copy silently
 * drops.
 *
 * Each instance owns its own reveal state and timer, so unmounting the page clears both — the
 * "hide it again when the user leaves" requirement needs no extra code at any call site.
 */
export function MaskedAccountNumber({
  value,
  placeholder = '•••• ••••',
  absent = 'Not available',
  className = '',
}: {
  value: string | null | undefined;
  /** What stands in for the number while hidden. */
  placeholder?: string;
  /** What to render when there is no number at all — most accounts have none. */
  absent?: string;
  className?: string;
}) {
  const [revealed, setRevealed] = useState(false);
  const remaskTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    // Cleared on unmount, so navigating away always re-hides rather than leaving a timer to fire
    // against a component that no longer exists.
    return () => {
      if (remaskTimer.current) clearTimeout(remaskTimer.current);
    };
  }, []);

  if (!value) return <span className={className}>{absent}</span>;

  function toggle() {
    if (remaskTimer.current) {
      clearTimeout(remaskTimer.current);
      remaskTimer.current = null;
    }
    setRevealed((wasRevealed) => {
      if (wasRevealed) return false;
      remaskTimer.current = setTimeout(() => {
        setRevealed(false);
        remaskTimer.current = null;
      }, AUTO_REMASK_MS);
      return true;
    });
  }

  return (
    <span className={`inline-flex items-center gap-1.5 ${className}`}>
      {revealed ? value : placeholder}
      <button
        type="button"
        onClick={toggle}
        title={revealed ? 'Hide account number' : 'Show account number'}
        aria-label={revealed ? 'Hide account number' : 'Show account number'}
        aria-pressed={revealed}
        className="text-muted hover:text-ink align-middle"
      >
        {revealed ? <EyeOff size={13} /> : <Eye size={13} />}
      </button>
    </span>
  );
}
