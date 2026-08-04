import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAdminAuth } from '../../context/AdminAuthContext';
import { Trash2 } from 'lucide-react';
import { adminTransactionsApi } from '../../api/endpoints';

export function TransactionsSection({ userId }: { userId: string }) {
  const { hasPermission } = useAdminAuth();
  const queryClient = useQueryClient();
  const canDelete = hasPermission('TRANSACTION_DELETE');

  const { data: transactions, isLoading } = useQuery({
    queryKey: ['admin-user-transactions', userId],
    queryFn: () => adminTransactionsApi.list(userId),
  });

  const deleteMutation = useMutation({
    mutationFn: (id: string) => adminTransactionsApi.delete(userId, id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin-user-transactions', userId] });
      void queryClient.invalidateQueries({ queryKey: ['admin-user-accounts', userId] });
      void queryClient.invalidateQueries({ queryKey: ['admin-user', userId] });
    },
  });

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <h3 className="text-sm font-semibold text-ink mb-3">Recent transactions</h3>
      <p className="text-xs text-muted mb-3">Most recent 50. For CSV import on a user's behalf, ask them to import it themselves.</p>
      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {!isLoading && (transactions ?? []).length === 0 && (
        <p className="text-sm text-muted">No transactions on file for this user.</p>
      )}
      <div className="space-y-1.5">
        {transactions?.map((t) => (
          <div key={t.id} className="flex items-center justify-between text-sm py-2 border-b border-border last:border-b-0">
            <div className="min-w-0">
              <p className="text-ink font-medium truncate">{t.description}</p>
              <p className="text-xs text-muted">{t.categoryName ?? 'Uncategorized'} · {new Date(t.date).toLocaleDateString()}</p>
            </div>
            <div className="flex items-center gap-3 flex-shrink-0">
              <span className={`font-semibold ${t.type === 'EXPENSE' ? 'text-danger' : 'text-success'}`}>
                {t.type === 'EXPENSE' ? '−' : '+'}₹{Math.abs(t.amount).toLocaleString('en-IN')}
              </span>
              {canDelete && (
                <button
                  type="button"
                  title="Delete"
                  disabled={deleteMutation.isPending}
                  onClick={() => {
                    if (confirm('Delete this transaction?')) deleteMutation.mutate(t.id);
                  }}
                  className="w-7 h-7 rounded-lg hover:bg-danger-bg text-muted hover:text-danger inline-flex items-center justify-center"
                >
                  <Trash2 size={13} />
                </button>
              )}
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}
