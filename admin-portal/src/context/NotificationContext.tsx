import { createContext, useCallback, useContext, useRef, useState } from 'react';
import type { ReactNode } from 'react';
import { CheckCircle2, AlertOctagon, X } from 'lucide-react';

export type ToastType = 'success' | 'error';

interface Toast {
  id: number;
  type: ToastType;
  message: string;
}

interface NotificationContextValue {
  notify: (type: ToastType, message: string) => void;
}

const NotificationContext = createContext<NotificationContextValue | null>(null);

// Success toasts clear themselves faster than error toasts -- an error is worth having time to
// actually read, a success confirmation is just "yep, that worked" and doesn't need to linger.
const DURATIONS: Record<ToastType, number> = { success: 4000, error: 6000 };

/**
 * Admin Portal Phase 6 -- a small toast/notification system so admin actions get visible
 * confirmation beyond a mutation silently succeeding, or an error that only shows up as a small
 * inline message inside whatever section triggered it (easy to miss if you've already scrolled
 * away, e.g. after a merge or a rule delete). Stacked top-right, auto-dismissing (see DURATIONS
 * above), manually dismissible early via the X button.
 *
 * Reference implementation wired into: MerchantRow's rename/merge mutations (UserDetail.tsx),
 * GlobalRules.tsx's create/update/delete mutations, and Users.tsx's suspend/reactivate mutations
 * -- the three mutation groups named in the roadmap for this phase. A future mutation adopting
 * this needs only `const notify = useNotify()` and a `notify.success(...)`/`notify.error(...)`
 * call in its onSuccess/onError, no changes here or to App.tsx (already wrapped once, globally).
 */
export function NotificationProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const nextId = useRef(0);

  const notify = useCallback((type: ToastType, message: string) => {
    const id = nextId.current++;
    setToasts((prev) => [...prev, { id, type, message }]);
    setTimeout(() => {
      setToasts((prev) => prev.filter((t) => t.id !== id));
    }, DURATIONS[type]);
  }, []);

  function dismiss(id: number) {
    setToasts((prev) => prev.filter((t) => t.id !== id));
  }

  return (
    <NotificationContext.Provider value={{ notify }}>
      {children}
      <div className="fixed top-4 right-4 z-[100] flex flex-col gap-2 w-80">
        {toasts.map((toast) => (
          <div
            key={toast.id}
            role="status"
            className={`flex items-start gap-2.5 rounded-lg px-3.5 py-2.5 shadow-soft border ${
              toast.type === 'success'
                ? 'bg-success-bg border-success/20 text-success'
                : 'bg-danger-bg border-danger/20 text-danger'
            }`}
          >
            {toast.type === 'success'
              ? <CheckCircle2 size={16} className="flex-shrink-0 mt-0.5" />
              : <AlertOctagon size={16} className="flex-shrink-0 mt-0.5" />}
            <p className="text-sm font-medium flex-1">{toast.message}</p>
            <button
              type="button"
              onClick={() => dismiss(toast.id)}
              aria-label="Dismiss notification"
              className="flex-shrink-0 opacity-70 hover:opacity-100"
            >
              <X size={14} />
            </button>
          </div>
        ))}
      </div>
    </NotificationContext.Provider>
  );
}

/** `success`/`error` rather than a single `notify(type, message)` call at every call site -- this
 *  is the ergonomic surface every mutation's onSuccess/onError actually reaches for. */
export function useNotify() {
  const ctx = useContext(NotificationContext);
  if (!ctx) throw new Error('useNotify must be used within NotificationProvider');
  return {
    success: (message: string) => ctx.notify('success', message),
    error: (message: string) => ctx.notify('error', message),
  };
}
