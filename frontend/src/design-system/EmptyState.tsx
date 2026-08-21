import type { ReactNode } from 'react';
import type { LucideIcon } from 'lucide-react';

/**
 * One consistent "nothing here yet" treatment (icon + heading + subtext + a real CTA) used to be
 * Dashboard-only (D-22's redesign) while every other page fell back to a bare italic line, in one
 * of three different color tokens for the same meaning. Extracted unchanged from Dashboard's own
 * `SectionEmptyState` -- this is a move, not a redesign.
 */
export function EmptyState({
  icon: Icon, iconBg, iconColor, title, desc, cta,
}: {
  icon: LucideIcon; iconBg: string; iconColor: string; title: string; desc: string; cta?: ReactNode;
}) {
  return (
    <div className="flex flex-col items-center text-center py-4 px-2">
      <div className={`w-12 h-12 rounded-full ${iconBg} flex items-center justify-center mb-3`}>
        <Icon size={22} className={iconColor} />
      </div>
      <p className="text-sm font-semibold text-ink mb-1">{title}</p>
      <p className="text-xs text-muted mb-4 max-w-[220px]">{desc}</p>
      {cta}
    </div>
  );
}
