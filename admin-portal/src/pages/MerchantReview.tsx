import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { BadgeCheck, GitMerge, Pencil, Trash2 } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { adminMerchantReviewApi } from '../api/endpoints';
import { formatWhen } from '../lib/formatWhen';
import type { MerchantReviewItem } from '../types';

const PAGE_SIZE = 25;

/**
 * The Merchant Review Center (WI4).
 *
 * The import engine resolves an unseen description by first-significant-token match — a heuristic
 * its own class doc calls "deliberately simple ... not fuzzy matching or NLP". So everything on
 * this page is a GUESS the engine made, and the operator's job is to confirm, correct or discard
 * it. Nothing here ever blocked an import; these merchants already exist and are already attached
 * to whatever the user imported.
 *
 * Two things shape the UI, and both come from the schema rather than from preference:
 *
 * 1. **Every action is scoped to the owning user.** merchants.user_id is NOT NULL and there is no
 *    canonical registry, so a merchant belongs to exactly one person. Merge candidates are drawn
 *    only from that user's own merchants; there is deliberately no cross-user merge to offer.
 * 2. **transactionCount decides which actions exist.** transactions.merchant_id is ON DELETE SET
 *    NULL, so discarding a merchant with history would silently strip the attribution from real
 *    ledger rows. Discard is therefore only offered at zero, and the row says why when it is not.
 */

function MerchantReviewContent() {
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState<MerchantReviewItem | null>(null);
  const [renaming, setRenaming] = useState('');
  const queryClient = useQueryClient();

  const queue = useQuery({
    queryKey: ['merchant-review', page],
    queryFn: () => adminMerchantReviewApi.queue({ page, size: PAGE_SIZE }),
  });

  // Deleting/approving the last row(s) on a page beyond the first would otherwise leave the
  // admin stranded on a now-empty page -- back off to the previous one so the list they land on
  // actually has something in it, same as Pagination.tsx never rendering "Page 2 of 1".
  const refresh = (removedCount = 1) => {
    setPage((p) => (p > 0 && (queue.data?.content.length ?? 0) <= removedCount ? p - 1 : p));
    void queryClient.invalidateQueries({ queryKey: ['merchant-review'] });
    setSelected(null);
  };

  const approve = useMutation({
    mutationFn: (row: MerchantReviewItem) => adminMerchantReviewApi.approve(row.userId, row.id),
    onSuccess: () => refresh(),
  });

  const approveAll = useMutation({
    mutationFn: (userId: string) => adminMerchantReviewApi.approveAll(userId),
    // approveAll can clear every row on the page belonging to this user, not just one.
    onSuccess: (_data, userId) =>
      refresh(queue.data?.content.filter((row) => row.userId === userId).length ?? 1),
  });

  const rename = useMutation({
    mutationFn: (vars: { row: MerchantReviewItem; name: string }) =>
      adminMerchantReviewApi.rename(vars.row.userId, vars.row.id, vars.name),
    onSuccess: () => refresh(),
  });

  const discard = useMutation({
    mutationFn: (row: MerchantReviewItem) => adminMerchantReviewApi.discard(row.userId, row.id),
    onSuccess: () => refresh(),
  });

  const merge = useMutation({
    mutationFn: (vars: { row: MerchantReviewItem; into: string }) =>
      adminMerchantReviewApi.merge(vars.row.userId, vars.row.id, vars.into),
    onSuccess: () => refresh(),
  });

  const columns: DataTableColumn<MerchantReviewItem>[] = [
    {
      header: 'Engine guessed',
      render: (row) => (
        <div>
          <p className="text-ink">{row.canonicalName}</p>
          <p className="text-muted text-xs">seen {formatWhen(row.createdAt)}</p>
        </div>
      ),
    },
    {
      header: 'Account',
      render: (row) => (
        <a className="text-accent hover:underline text-sm" href={`/users/${row.userId}`}>
          {row.userEmail ?? row.userId.slice(0, 8)}
        </a>
      ),
    },
    {
      header: 'On the ledger',
      // The number that decides what an operator may do. Zero means the guess was never real.
      render: (row) =>
        row.transactionCount === 0 ? (
          <span className="text-muted text-xs">no transactions</span>
        ) : (
          <span className="text-ink">
            {row.transactionCount} transaction{row.transactionCount === 1 ? '' : 's'}
          </span>
        ),
    },
    {
      header: '',
      render: (row) => (
        <div className="flex gap-2">
          <button
            className="text-xs text-accent hover:underline disabled:opacity-50"
            disabled={approve.isPending}
            onClick={() => approve.mutate(row)}
          >
            Approve
          </button>
          <button
            className="text-xs text-accent hover:underline"
            onClick={() => {
              setSelected(row);
              setRenaming(row.canonicalName);
            }}
          >
            Review
          </button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <p className="text-muted text-sm">
        Merchants the import engine created from descriptions it had not seen before. Approving
        confirms the guess; renaming corrects it; merging folds it into a merchant this account
        already has. Nothing here blocked an import.
      </p>

      <DataTable
        columns={columns}
        rows={queue.data?.content ?? []}
        keyFor={(row) => row.id}
        loading={queue.isLoading}
        emptyMessage="Nothing awaiting review. Every merchant has been confirmed."
      />

      {queue.data && (
        <Pagination
          page={page}
          totalPages={queue.data.totalPages}
          totalElements={queue.data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}

      {selected && (
        <ReviewPanel
          row={selected}
          renaming={renaming}
          setRenaming={setRenaming}
          busy={rename.isPending || discard.isPending || merge.isPending || approveAll.isPending}
          onClose={() => setSelected(null)}
          onRename={() => rename.mutate({ row: selected, name: renaming })}
          onDiscard={() => discard.mutate(selected)}
          onMerge={(into) => merge.mutate({ row: selected, into })}
          onApproveAllForUser={() => approveAll.mutate(selected.userId)}
        />
      )}
    </div>
  );
}

/**
 * The decision panel for one guessed merchant.
 *
 * Merge candidates are fetched per-merchant rather than held in the list, because they are the
 * owner's OTHER merchants — a different query per row, and one nobody needs until they open this.
 */
function ReviewPanel({
  row,
  renaming,
  setRenaming,
  busy,
  onClose,
  onRename,
  onDiscard,
  onMerge,
  onApproveAllForUser,
}: {
  row: MerchantReviewItem;
  renaming: string;
  setRenaming: (value: string) => void;
  busy: boolean;
  onClose: () => void;
  onRename: () => void;
  onDiscard: () => void;
  onMerge: (into: string) => void;
  onApproveAllForUser: () => void;
}) {
  const candidates = useQuery({
    queryKey: ['merchant-review-candidates', row.userId, row.id],
    queryFn: () => adminMerchantReviewApi.mergeCandidates(row.userId, row.id),
  });

  const canDiscard = row.transactionCount === 0;

  return (
    <div className="bg-card border border-border rounded-xl2 p-6 space-y-5">
      <div className="flex items-start justify-between">
        <div>
          <h2 className="text-lg font-semibold text-ink">{row.canonicalName}</h2>
          <p className="text-muted text-xs mt-1">
            {row.userEmail ?? row.userId} · {row.transactionCount} transaction
            {row.transactionCount === 1 ? '' : 's'}
          </p>
        </div>
        <button className="text-muted hover:text-ink text-sm" onClick={onClose}>
          Close
        </button>
      </div>

      <div className="space-y-2">
        <label className="text-muted text-xs block" htmlFor="canonicalName">
          Correct the name
        </label>
        <div className="flex gap-2">
          <input
            id="canonicalName"
            className="flex-1 rounded-lg border border-border bg-bg px-3 py-2 text-sm text-ink"
            value={renaming}
            onChange={(e) => setRenaming(e.target.value)}
          />
          <button
            className="rounded-lg bg-accent px-3 py-2 text-sm text-white disabled:opacity-50"
            disabled={busy || !renaming.trim() || renaming === row.canonicalName}
            onClick={onRename}
          >
            <Pencil className="inline h-4 w-4 mr-1" /> Rename &amp; approve
          </button>
        </div>
      </div>

      <div className="space-y-2">
        <p className="text-muted text-xs">
          Or fold it into a merchant this account already has. Merging repoints its transactions,
          aliases and learning history first — nothing is lost.
        </p>
        {candidates.isLoading && <p className="text-muted text-sm">Loading candidates…</p>}
        {candidates.data?.length === 0 && (
          <p className="text-muted text-sm">This account has no other approved merchants yet.</p>
        )}
        <div className="flex flex-wrap gap-2">
          {(candidates.data ?? []).map((candidate) => (
            <button
              key={candidate.id}
              className="rounded-lg border border-border px-3 py-1.5 text-sm text-ink hover:bg-bg disabled:opacity-50"
              disabled={busy}
              onClick={() => onMerge(candidate.id)}
            >
              <GitMerge className="inline h-3.5 w-3.5 mr-1" />
              {candidate.canonicalName}
            </button>
          ))}
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-3 pt-3 border-t border-border">
        <button
          className="rounded-lg border border-border px-3 py-2 text-sm text-ink disabled:opacity-50"
          disabled={busy}
          onClick={onApproveAllForUser}
        >
          <BadgeCheck className="inline h-4 w-4 mr-1" /> Approve all for this account
        </button>

        {/* Discard is only offered when nothing points at the merchant. transactions.merchant_id
            is ON DELETE SET NULL, so deleting one with history would silently strip the merchant
            from real ledger rows -- the backend refuses it, and the UI does not offer a button
            whose only outcome is that refusal. */}
        {canDiscard ? (
          <button
            className="rounded-lg border border-red-500/30 text-red-400 px-3 py-2 text-sm disabled:opacity-50"
            disabled={busy}
            onClick={onDiscard}
          >
            <Trash2 className="inline h-4 w-4 mr-1" /> Discard
          </button>
        ) : (
          <p className="text-muted text-xs">
            Cannot discard — {row.transactionCount} transaction
            {row.transactionCount === 1 ? ' is' : 's are'} attributed to this merchant. Merge it
            instead.
          </p>
        )}
      </div>
    </div>
  );
}

export default function MerchantReview() {
  return (
    <AdminLayout
      title="Merchant Review"
      subtitle="Merchants the import engine guessed, awaiting confirmation."
    >
      <RequirePermission permission="MERCHANT_REVIEW">
        <MerchantReviewContent />
      </RequirePermission>
    </AdminLayout>
  );
}
