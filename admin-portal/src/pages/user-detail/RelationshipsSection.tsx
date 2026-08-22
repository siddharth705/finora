import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus, Trash2, Users } from 'lucide-react';
import { useNotify } from '../../context/NotificationContext';
import { adminUserRelationshipsApi } from '../../api/endpoints';
import type { CreateRelationshipRequest, RelationshipDto, UpdateRelationshipRequest } from '../../types';
import { errorMessage } from './errorMessage';
import { ConfirmDialog } from '../../components/ConfirmDialog';

const RELATIONSHIP_TYPES = ['FAMILY', 'FRIEND', 'OWN_ACCOUNT', 'OTHER'];
const IDENTIFIER_TYPES = ['UPI_ID', 'ACCOUNT_LAST4', 'NAME_PATTERN'];
const BLANK_RELATIONSHIP_FORM: CreateRelationshipRequest = {
  label: '', relationshipType: 'FAMILY', identifiers: [{ identifierType: 'UPI_ID', identifierValue: '' }],
};

/** Compact create/edit form for a single relationship, embedded inline in RelationshipsSection --
 *  same "no floating modal" convention as InlineRuleForm above. Only a single identifier row --
 *  the self-service page supported adding several, but a support-assisted edit rarely needs more
 *  than one and this keeps the form from ballooning; multi-identifier relationships created
 *  elsewhere still display and merge correctly here, editing just replaces down to one. */

export function InlineRelationshipForm({
  initial, submitting, error, onCancel, onSubmit,
}: {
  initial: CreateRelationshipRequest;
  submitting: boolean;
  error: string | null;
  onCancel: () => void;
  onSubmit: (values: CreateRelationshipRequest) => void;
}) {
  const [form, setForm] = useState<CreateRelationshipRequest>(initial);
  const identifier = form.identifiers[0] ?? { identifierType: 'UPI_ID', identifierValue: '' };

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(form);
      }}
      className="bg-bg border border-border rounded-lg p-3.5 space-y-2.5"
    >
      <div className="grid gap-2 md:grid-cols-2">
        <input
          required
          placeholder="Label (e.g. Mom, Roommate)"
          value={form.label}
          onChange={(e) => setForm({ ...form, label: e.target.value })}
          className="md:col-span-2 bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        />
        <select
          aria-label="Relationship type"
          value={form.relationshipType}
          onChange={(e) => setForm({ ...form, relationshipType: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        >
          {RELATIONSHIP_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <select
          aria-label="Identifier type"
          value={identifier.identifierType}
          onChange={(e) => setForm({ ...form, identifiers: [{ ...identifier, identifierType: e.target.value }] })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        >
          {IDENTIFIER_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
        </select>
        <input
          required
          placeholder="Identifier value"
          value={identifier.identifierValue}
          onChange={(e) => setForm({ ...form, identifiers: [{ ...identifier, identifierValue: e.target.value }] })}
          className="md:col-span-2 bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        />
      </div>
      {error && <p className="text-xs text-danger">{error}</p>}
      <div className="flex items-center gap-2">
        <button
          type="submit"
          disabled={submitting}
          className="text-xs font-semibold text-on-primary bg-primary hover:bg-primary-dark rounded-lg px-3 py-1.5 disabled:opacity-50"
        >
          {submitting ? 'Saving…' : 'Save'}
        </button>
        <button type="button" onClick={onCancel} className="text-xs text-muted px-3 py-1.5">
          Cancel
        </button>
      </div>
    </form>
  );
}

export function RelationshipRow({ userId, relationship, allRelationships }: {
  userId: string; relationship: RelationshipDto; allRelationships: RelationshipDto[];
}) {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const [editing, setEditing] = useState(false);
  const [mergeFrom, setMergeFrom] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [confirmMerge, setConfirmMerge] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['admin-user-relationships', userId] });
  }

  const updateMutation = useMutation({
    mutationFn: (values: UpdateRelationshipRequest) => adminUserRelationshipsApi.update(userId, relationship.id, values),
    onSuccess: () => {
      setEditing(false);
      setError(null);
      invalidate();
      notify.success('Relationship updated.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to update this relationship.');
      setError(msg);
      notify.error(msg);
    },
  });
  const mergeMutation = useMutation({
    mutationFn: (mergeFromRelationshipId: string) =>
      adminUserRelationshipsApi.merge(userId, relationship.id, { mergeFromRelationshipId }),
    onSuccess: () => {
      setMergeFrom('');
      invalidate();
      notify.success('Relationships merged.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to merge these relationships.');
      setError(msg);
      notify.error(msg);
    },
  });
  // Bug fix: this mutation had no onError at all -- unlike every other mutation in this file
  // (including its own siblings above), a failed delete (403, FK conflict) closed the confirm
  // dialog and left the user with zero feedback that nothing actually happened.
  const deleteMutation = useMutation({
    mutationFn: () => adminUserRelationshipsApi.delete(userId, relationship.id),
    onSuccess: () => {
      invalidate();
      notify.success('Relationship deleted.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to delete this relationship.');
      setError(msg);
      notify.error(msg);
    },
  });

  const otherRelationships = allRelationships.filter((r) => r.id !== relationship.id);

  if (editing) {
    return (
      <div className="py-2.5 border-b border-border last:border-b-0">
        <InlineRelationshipForm
          initial={{
            label: relationship.label,
            relationshipType: relationship.relationshipType,
            linkedAccountId: relationship.linkedAccountId ?? undefined,
            identifiers: relationship.identifiers.map((i) => ({
              identifierType: i.identifierType, identifierValue: i.identifierValue,
            })),
          }}
          submitting={updateMutation.isPending}
          error={error}
          onCancel={() => {
            setEditing(false);
            setError(null);
          }}
          onSubmit={(values) => updateMutation.mutate(values)}
        />
      </div>
    );
  }

  return (
    <div className="flex items-center justify-between text-sm py-2.5 border-b border-border last:border-b-0 gap-3">
      <div className="min-w-0 flex-1">
        <p className="text-ink font-medium truncate">{relationship.label}</p>
        <p className="text-xs text-muted">
          {relationship.relationshipType} · {relationship.identifiers.length} identifier
          {relationship.identifiers.length === 1 ? '' : 's'}
        </p>
        {error && <p className="text-xs text-danger mt-1">{error}</p>}
      </div>
      <div className="flex items-center gap-1.5 flex-shrink-0">
        {otherRelationships.length > 0 && (
          <>
            <select
              aria-label="Merge from"
              value={mergeFrom}
              onChange={(e) => setMergeFrom(e.target.value)}
              className="bg-bg border border-border rounded-lg px-2 py-1.5 text-xs"
            >
              <option value="">Merge from…</option>
              {otherRelationships.map((r) => <option key={r.id} value={r.id}>{r.label}</option>)}
            </select>
            <button
              type="button"
              disabled={!mergeFrom || mergeMutation.isPending}
              onClick={() => setConfirmMerge(true)}
              className="text-xs font-semibold text-primary px-2 py-1.5 disabled:opacity-50"
            >
              Merge
            </button>
          </>
        )}
        <button
          type="button"
          title="Edit"
          onClick={() => setEditing(true)}
          className="w-7 h-7 rounded-lg hover:bg-bg text-muted hover:text-ink inline-flex items-center justify-center"
        >
          <Pencil size={13} />
        </button>
        <button
          type="button"
          title="Delete"
          disabled={deleteMutation.isPending}
          onClick={() => setConfirmDelete(true)}
          className="w-7 h-7 rounded-lg hover:bg-danger-bg text-muted hover:text-danger inline-flex items-center justify-center"
        >
          <Trash2 size={13} />
        </button>
      </div>

      {confirmMerge && (
        <ConfirmDialog
          title={`Merge "${otherRelationships.find((r) => r.id === mergeFrom)?.label}" into "${relationship.label}"?`}
          message="This can't be undone."
          confirmLabel="Merge"
          danger
          busy={mergeMutation.isPending}
          onConfirm={() => { setConfirmMerge(false); mergeMutation.mutate(mergeFrom); }}
          onCancel={() => setConfirmMerge(false)}
        />
      )}

      {confirmDelete && (
        <ConfirmDialog
          title="Delete this relationship?"
          message="This can't be undone."
          confirmLabel="Delete"
          danger
          busy={deleteMutation.isPending}
          onConfirm={() => { setConfirmDelete(false); deleteMutation.mutate(); }}
          onCancel={() => setConfirmDelete(false)}
        />
      )}
    </div>
  );
}

/** Per-user relationship (family/friend/own-account) tagging -- admin-only now that the
 *  self-service Relationships tab (bundled into the old Rules page) has been retired. Reuses
 *  RelationshipService's exact USER-scope CRUD/merge logic via AdminUserRelationshipController,
 *  same shape as RulesSection above. */
export function RelationshipsSection({ userId }: { userId: string }) {
  const [showCreate, setShowCreate] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const { data: relationships, isLoading, isError } = useQuery({
    queryKey: ['admin-user-relationships', userId],
    queryFn: () => adminUserRelationshipsApi.list(userId),
  });

  const createMutation = useMutation({
    mutationFn: (values: CreateRelationshipRequest) => adminUserRelationshipsApi.create(userId, values),
    onSuccess: () => {
      setShowCreate(false);
      setCreateError(null);
      void queryClient.invalidateQueries({ queryKey: ['admin-user-relationships', userId] });
    },
    onError: (err: any) => setCreateError(errorMessage(err, 'Failed to create relationship.')),
  });

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <Users size={15} className="text-primary" />
          <h3 className="text-sm font-semibold text-ink">Relationships</h3>
        </div>
        {!showCreate && (
          <button
            type="button"
            onClick={() => {
              setShowCreate(true);
              setCreateError(null);
            }}
            className="inline-flex items-center gap-1 text-xs font-semibold text-primary"
          >
            <Plus size={13} /> New relationship
          </button>
        )}
      </div>
      {showCreate && (
        <div className="mb-3">
          <InlineRelationshipForm
            initial={BLANK_RELATIONSHIP_FORM}
            submitting={createMutation.isPending}
            error={createError}
            onCancel={() => {
              setShowCreate(false);
              setCreateError(null);
            }}
            onSubmit={(values) => createMutation.mutate(values)}
          />
        </div>
      )}
      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {!isLoading && isError && (
        <p className="text-sm text-danger">Couldn't load relationships for this user — please try again later.</p>
      )}
      {!isLoading && !isError && (relationships ?? []).length === 0 && (
        <p className="text-sm text-muted">No relationships tagged for this user yet.</p>
      )}
      <div>
        {!isError && relationships?.map((r) => (
          <RelationshipRow key={r.id} userId={userId} relationship={r} allRelationships={relationships} />
        ))}
      </div>
    </div>
  );
}

/** Read-only Learning Engine visibility for a specific user -- AdminUserLearningController
 *  proxies the exact same MerchantLearningService.timeline()/summary() the self-service Learning
 *  Engine page used before it was retired. Stays read-only here on purpose: undo/reset are actions
 *  on a specific merchant, so they live on MerchantsSection above, not duplicated here. */
