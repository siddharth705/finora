import { Link } from 'react-router-dom';
import type { LucideIcon } from 'lucide-react';

const className = 'flex flex-col items-center gap-1.5 text-center p-3 rounded-lg bg-bg hover:bg-primary-light text-ink hover:text-primary transition-colors';

/** One tile in Dashboard's Quick Actions grid -- a `Link` when `to` is given, a `button` otherwise. */
export function QuickActionCard({
  icon: Icon, label, to, onClick,
}: {
  icon: LucideIcon; label: string; to?: string; onClick?: () => void;
}) {
  const body = (
    <>
      <Icon size={18} />
      <span className="text-[11px] font-medium leading-tight">{label}</span>
    </>
  );
  return to
    ? <Link to={to} className={className}>{body}</Link>
    : <button type="button" onClick={onClick} className={className}>{body}</button>;
}
