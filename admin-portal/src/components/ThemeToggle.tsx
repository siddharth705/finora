import { useEffect, useRef, useState } from 'react';
import { Sun, Moon, Monitor, Check } from 'lucide-react';
import { useTheme, type ThemeSetting } from '../context/ThemeContext';

const THEME_OPTIONS: { value: ThemeSetting; label: string; icon: typeof Sun }[] = [
  { value: 'light', label: 'Light', icon: Sun },
  { value: 'dark', label: 'Dark', icon: Moon },
  { value: 'system', label: 'System', icon: Monitor },
];

export function ThemeToggle() {
  const { theme, resolvedTheme, setTheme } = useTheme();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  // Matches GlobalSearch's own click-outside convention (containerRef + mousedown listener)
  // rather than the user app's fixed-backdrop overlay, for consistency within this codebase.
  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    function handleEscape(event: KeyboardEvent) {
      if (event.key === 'Escape') setOpen(false);
    }
    document.addEventListener('mousedown', handleClickOutside);
    document.addEventListener('keydown', handleEscape);
    return () => {
      document.removeEventListener('mousedown', handleClickOutside);
      document.removeEventListener('keydown', handleEscape);
    };
  }, []);

  const ThemeIcon = theme === 'light' ? Sun : theme === 'dark' ? Moon : Monitor;

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        title="Theme"
        className="w-9 h-9 rounded-full bg-card border border-border shadow-card flex items-center justify-center text-muted hover:text-ink"
      >
        <ThemeIcon size={16} />
      </button>
      {open && (
        <div className="absolute right-0 mt-2 w-52 bg-card border border-border rounded-xl2 shadow-card py-1.5 z-50">
          <p className="px-3.5 py-2 text-[11px] uppercase tracking-wide text-muted">Theme</p>
          {THEME_OPTIONS.map(({ value, label, icon: Icon }) => (
            <button
              key={value}
              type="button"
              onClick={() => {
                setTheme(value);
                setOpen(false);
              }}
              className="w-full flex items-center gap-2.5 px-3.5 py-2.5 text-sm text-ink hover:bg-bg"
            >
              <Icon size={15} className="text-muted" />
              <span className="flex-1 text-left">{label}</span>
              {theme === value && <Check size={15} className="text-primary" />}
            </button>
          ))}
          <p className="px-3.5 pt-1 pb-2 text-[11px] text-muted">
            Currently showing {resolvedTheme === 'dark' ? 'dark' : 'light'}
            {theme === 'system' ? ' (following your device)' : ''}.
          </p>
        </div>
      )}
    </div>
  );
}
