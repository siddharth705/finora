import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { Bell, ShieldAlert, CheckCircle2 } from 'lucide-react';
import { useDashboardOverview } from '../hooks/useDashboardOverview';
import { needsAttentionItems } from '../lib/needsAttentionItems';
import type { AlertDto } from '../types';

function AlertRow({ alert }: { alert: AlertDto }) {
  const critical = alert.severity === 'critical';
  return (
    <div className={`flex items-start gap-2.5 px-4 py-2.5 ${critical ? 'bg-danger-bg' : 'bg-warning-bg'}`}>
      <ShieldAlert size={14} className={`flex-shrink-0 mt-0.5 ${critical ? 'text-danger' : 'text-warning'}`} />
      <div className="min-w-0">
        <p className={`text-xs font-semibold ${critical ? 'text-danger' : 'text-warning'}`}>{alert.title}</p>
        <p className={`text-xs mt-0.5 ${critical ? 'text-danger' : 'text-warning'}`}>{alert.detail}</p>
      </div>
    </div>
  );
}

/**
 * Header bell (dashboard redesign PR3) -- reuses the exact overview data Dashboard.tsx already
 * renders (health alerts + needs-attention counts, via the shared useDashboardOverview cache
 * entry), just also reachable globally from any admin page rather than only from the Dashboard
 * itself. Deliberately not the frozen consumer notification-platform design (outbox/worker/
 * encrypted tokens/i18n) -- no new entity, no persistence, no delivery mechanism, just a second
 * read of data already being fetched.
 */
export function NotificationBell() {
  const { data } = useDashboardOverview();
  const [open, setOpen] = useState(false);
  const containerRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (containerRef.current && !containerRef.current.contains(event.target as Node)) {
        setOpen(false);
      }
    }
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, []);

  // No overview data (still loading, or this account lacks PLATFORM_STATS_VIEW so the query
  // never ran) -- no bell at all, rather than one that opens onto an empty or 403'd panel. Same
  // pattern GlobalSearch uses for a control that can't do anything for this account.
  if (!data) return null;

  const attentionItems = needsAttentionItems(data.needsAttention);
  const count = data.alerts.length + attentionItems.length;

  return (
    <div ref={containerRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen((o) => !o)}
        aria-label={count > 0 ? `${count} item${count === 1 ? '' : 's'} need${count === 1 ? 's' : ''} attention` : 'No alerts'}
        className="relative w-9 h-9 rounded-full flex items-center justify-center text-muted hover:text-ink hover:bg-black/5 transition-colors"
      >
        <Bell size={17} />
        {count > 0 && (
          <span className="absolute top-1 right-1 min-w-[16px] h-4 px-1 rounded-full bg-danger text-white text-[10px] font-bold flex items-center justify-center">
            {count > 9 ? '9+' : count}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 mt-2 w-80 bg-card border border-border rounded-xl2 shadow-card max-h-96 overflow-y-auto z-50 divide-y divide-border">
          {count === 0 ? (
            <div className="flex items-center gap-2 text-sm text-success px-4 py-4">
              <CheckCircle2 size={15} />
              <span>Nothing needs attention right now.</span>
            </div>
          ) : (
            <>
              {data.alerts.map((alert) => <AlertRow key={alert.title} alert={alert} />)}
              {attentionItems.map(({ count: itemCount, icon: Icon, label, to, linkLabel }) => (
                <div key={label} className="flex items-start gap-2.5 px-4 py-2.5">
                  <Icon size={14} className="text-warning flex-shrink-0 mt-0.5" />
                  <div className="min-w-0">
                    <p className="text-xs text-ink">
                      <span className="font-mono font-bold">{itemCount}</span> {label}
                    </p>
                    {to && (
                      <Link
                        to={to}
                        onClick={() => setOpen(false)}
                        className="text-xs text-primary font-medium underline underline-offset-2"
                      >
                        {linkLabel} →
                      </Link>
                    )}
                  </div>
                </div>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  );
}
