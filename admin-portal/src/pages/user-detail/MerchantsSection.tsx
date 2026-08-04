import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Pencil, Store } from 'lucide-react';
import { useAdminAuth } from '../../context/AdminAuthContext';
import { useNotify } from '../../context/NotificationContext';
import { adminUserMerchantsApi } from '../../api/endpoints';
import type { CreateRuleRequest, MerchantDto } from '../../types';
import { errorMessage } from './errorMessage';

export function MerchantRow({
  userId, merchant, allMerchants, canManage,
}: {
  userId: string;
  merchant: MerchantDto;
  allMerchants: MerchantDto[];
  canManage: boolean;
}) {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState(merchant.canonicalName);
  const [mergeFrom, setMergeFrom] = useState('');
  const [error, setError] = useState<string | null>(null);

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['admin-user-merchants', userId] });
    // Undo/reset write MerchantLearningAudit rows, which LearningSection reads -- without this
    // its timeline and summary keep showing pre-action state until a manual refresh.
    void queryClient.invalidateQueries({ queryKey: ['admin-user-learning-summary', userId] });
    void queryClient.invalidateQueries({ queryKey: ['admin-user-learning-timeline', userId] });
  }

  const renameMutation = useMutation({
    mutationFn: () => adminUserMerchantsApi.update(userId, merchant.id, { canonicalName: name.trim() }),
    onSuccess: () => {
      setEditing(false);
      invalidate();
      notify.success('Merchant renamed.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to rename this merchant.');
      setError(msg);
      notify.error(msg);
    },
  });
  const mergeMutation = useMutation({
    mutationFn: (mergeFromMerchantId: string) =>
      adminUserMerchantsApi.merge(userId, merchant.id, { mergeFromMerchantId }),
    onSuccess: () => {
      setMergeFrom('');
      invalidate();
      notify.success('Merchants merged.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to merge these merchants.');
      setError(msg);
      notify.error(msg);
    },
  });
  const undoMutation = useMutation({
    mutationFn: () => adminUserMerchantsApi.undo(userId, merchant.id),
    onSuccess: () => {
      invalidate();
      notify.success('Last learning event undone.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to undo the last learning event.');
      setError(msg);
      notify.error(msg);
    },
  });
  const resetLearningMutation = useMutation({
    mutationFn: () => adminUserMerchantsApi.resetLearning(userId, merchant.id),
    onSuccess: () => {
      invalidate();
      notify.success('Learning reset for this merchant.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to reset learning for this merchant.');
      setError(msg);
      notify.error(msg);
    },
  });

  // Every OTHER merchant this same user has -- merging absorbs one of them into this row (see
  // MerchantService.merge()'s doc comment on the backend for exactly what that repoints).
  const otherMerchants = allMerchants.filter((m) => m.id !== merchant.id);
  // Both actions operate on learned categories, so there's nothing to act on until this merchant
  // has at least one.
  const hasLearning = merchant.topCategory !== null;

  return (
    <div className="flex items-center justify-between text-sm py-2.5 border-b border-border last:border-b-0 gap-3">
      <div className="min-w-0 flex-1">
        {editing ? (
          <div className="flex items-center gap-1.5">
            <input
              value={name}
              onChange={(e) => setName(e.target.value)}
              className="flex-1 bg-bg border border-border rounded-lg px-2.5 py-1.5 text-xs"
            />
            <button
              type="button"
              disabled={renameMutation.isPending}
              onClick={() => renameMutation.mutate()}
              className="text-xs font-semibold text-primary px-2 py-1.5"
            >
              Save
            </button>
            <button
              type="button"
              onClick={() => {
                setEditing(false);
                setName(merchant.canonicalName);
              }}
              className="text-xs text-muted px-2 py-1.5"
            >
              Cancel
            </button>
          </div>
        ) : (
          <>
            <p className="text-ink font-medium truncate">{merchant.canonicalName}</p>
            <p className="text-xs text-muted">
              {merchant.topCategory
                ? `${merchant.topCategory} (${merchant.topCategoryConfidence}% confidence)`
                : 'No confirmed category yet'}
            </p>
          </>
        )}
        {error && <p className="text-xs text-danger mt-1">{error}</p>}
      </div>
      {canManage && !editing && (
        <div className="flex items-center gap-2 flex-shrink-0">
          {otherMerchants.length > 0 && (
            <>
              <select
                aria-label="Merge from"
                value={mergeFrom}
                onChange={(e) => setMergeFrom(e.target.value)}
                className="bg-bg border border-border rounded-lg px-2 py-1.5 text-xs"
              >
                <option value="">Merge from…</option>
                {otherMerchants.map((m) => <option key={m.id} value={m.id}>{m.canonicalName}</option>)}
              </select>
              <button
                type="button"
                disabled={!mergeFrom || mergeMutation.isPending}
                onClick={() => {
                  const fromName = otherMerchants.find((m) => m.id === mergeFrom)?.canonicalName;
                  if (confirm(`Merge "${fromName}" into "${merchant.canonicalName}"? This can't be undone.`)) {
                    mergeMutation.mutate(mergeFrom);
                  }
                }}
                className="text-xs font-semibold text-primary px-2 py-1.5 disabled:opacity-50"
              >
                Merge
              </button>
            </>
          )}
          {hasLearning && (
            <>
              <button
                type="button"
                disabled={undoMutation.isPending}
                onClick={() => {
                  if (confirm(`Undo the last learning event for "${merchant.canonicalName}"?`)) {
                    undoMutation.mutate();
                  }
                }}
                className="text-xs font-semibold text-primary px-2 py-1.5 disabled:opacity-50"
              >
                Undo
              </button>
              <button
                type="button"
                disabled={resetLearningMutation.isPending}
                onClick={() => {
                  if (confirm(`Reset ALL learned categories for "${merchant.canonicalName}"? This can't be undone.`)) {
                    resetLearningMutation.mutate();
                  }
                }}
                className="text-xs font-semibold text-danger px-2 py-1.5 disabled:opacity-50"
              >
                Reset learning
              </button>
            </>
          )}
          <button
            type="button"
            title="Rename"
            onClick={() => setEditing(true)}
            className="w-7 h-7 rounded-lg hover:bg-bg text-muted hover:text-ink inline-flex items-center justify-center"
          >
            <Pencil size={13} />
          </button>
        </div>
      )}
    </div>
  );
}

/** Per-user merchant management -- admin-only now that the self-service Merchant Management
 *  console has been retired. Rename/merge plus Undo/Reset Learning (AdminUserMerchantController's
 *  full surface, including the three actions originally left off this proxy back when users could
 *  still do them themselves -- see that controller's class comment). */
export function MerchantsSection({ userId }: { userId: string }) {
  const { hasPermission } = useAdminAuth();
  const canManage = hasPermission('MERCHANT_MANAGE');

  const { data: merchants, isLoading } = useQuery({
    queryKey: ['admin-user-merchants', userId],
    queryFn: () => adminUserMerchantsApi.list(userId),
  });

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center gap-2 mb-3">
        <Store size={15} className="text-primary" />
        <h3 className="text-sm font-semibold text-ink">Merchants</h3>
      </div>
      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {!isLoading && (merchants ?? []).length === 0 && (
        <p className="text-sm text-muted">No merchants recorded for this user yet.</p>
      )}
      <div>
        {merchants?.map((m) => (
          <MerchantRow key={m.id} userId={userId} merchant={m} allMerchants={merchants} canManage={canManage} />
        ))}
      </div>
    </div>
  );
}

const RULE_FIELDS = ['DESCRIPTION', 'AMOUNT', 'MERCHANT', 'ACCOUNT_TYPE'];
const RULE_OPERATORS = ['CONTAINS', 'EQUALS', 'STARTS_WITH', 'GT', 'LT', 'BETWEEN'];
const RULE_ACTION_TYPES = ['ASSIGN_CATEGORY', 'MARK_TRANSFER', 'MARK_INVESTMENT', 'MARK_SUBSCRIPTION', 'ADD_TAG'];
const BLANK_RULE_FORM: CreateRuleRequest = {
  field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: '', actionType: 'ASSIGN_CATEGORY', actionValue: '', priority: 100,
};

/** Compact create/edit form for a single USER-scope rule, embedded inline in RulesSection --
 *  deliberately not a floating modal (see FormPanel's own comment on why this app avoids those).
 *  Kept smaller than GlobalRules.tsx's RuleForm (no test-match panel here) since this is a
 *  secondary admin-proxy surface, not the primary rule-authoring workflow. */
