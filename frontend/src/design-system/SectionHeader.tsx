import { Link } from 'react-router-dom';

/**
 * The heading + "View All" link pair Dashboard copy-pasted 5 times, plus the older pages' own
 * `text-xs uppercase text-gray-500` section labels (Reports/Investments/Insights) -- one visual
 * weight for "here's what this card holds" instead of two.
 */
export function SectionHeader({
  title, viewAllTo, size = 'md',
}: {
  title: string; viewAllTo?: string; size?: 'md' | 'sm';
}) {
  return (
    <div className="flex items-center justify-between mb-4">
      <h2 className={`font-semibold text-ink ${size === 'sm' ? 'text-sm' : ''}`}>{title}</h2>
      {viewAllTo && (
        <Link to={viewAllTo} className="text-xs text-primary font-medium">View All</Link>
      )}
    </div>
  );
}
