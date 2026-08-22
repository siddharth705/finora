import { useState } from 'react';
import { Eye, EyeOff } from 'lucide-react';

interface PasswordInputProps {
  value: string;
  onChange: (value: string) => void;
  onBlur?: () => void;
  required?: boolean;
  minLength?: number;
  maxLength?: number;
  className?: string;
  placeholder?: string;
  autoComplete?: string;
  autoFocus?: boolean;
  id?: string;
}

// Direct port of the user app's PasswordInput (finora/frontend/src/components/PasswordInput.tsx).
export function PasswordInput({
  value, onChange, onBlur, required, minLength, maxLength, className, placeholder, autoComplete, autoFocus, id,
}: PasswordInputProps) {
  const [visible, setVisible] = useState(false);

  return (
    <div className="relative">
      <input
        id={id}
        type={visible ? 'text' : 'password'}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onBlur={onBlur}
        required={required}
        minLength={minLength}
        maxLength={maxLength}
        placeholder={placeholder}
        autoComplete={autoComplete}
        autoFocus={autoFocus}
        className={className ?? 'w-full bg-bg border border-border rounded-lg px-3.5 py-2.5 pr-10 text-sm focus:outline-none focus:ring-2 focus:ring-primary/30'}
      />
      <button
        type="button"
        onClick={() => setVisible((v) => !v)}
        tabIndex={-1}
        className="absolute right-3 top-1/2 -translate-y-1/2 text-muted hover:text-ink"
        aria-label={visible ? 'Hide password' : 'Show password'}
      >
        {visible ? <EyeOff size={16} /> : <Eye size={16} />}
      </button>
    </div>
  );
}
