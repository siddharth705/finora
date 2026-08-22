import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus, Trash2, ListFilter } from 'lucide-react';
import { adminUserRulesApi } from '../../api/endpoints';
import type { CreateRelationshipRequest, CreateRuleRequest, RuleDto, UpdateRuleRequest } from '../../types';
import { errorMessage } from './errorMessage';
import { ConfirmDialog } from '../../components/ConfirmDialog';

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

export function InlineRuleForm({
  initial, submitting, error, onCancel, onSubmit,
}: {
  initial: CreateRuleRequest;
  submitting: boolean;
  error: string | null;
  onCancel: () => void;
  onSubmit: (values: CreateRuleRequest) => void;
}) {
  const [form, setForm] = useState<CreateRuleRequest>(initial);

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(form);
      }}
      className="bg-bg border border-border rounded-lg p-3.5 space-y-2.5"
    >
      <div className="grid gap-2 md:grid-cols-2">
        {/* Bug fix: these three selects had no accessible name at all -- a real, critical axe
            "select-name" violation (distinct from the "label" rule fixed elsewhere: a placeholder
            attribute doesn't exist on <select>, so there was nothing to fall back on). aria-label
            matches the pattern RelationshipsSection.tsx's selects already use. */}
        <select
          aria-label="Field"
          value={form.field}
          onChange={(e) => setForm({ ...form, field: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        >
          {RULE_FIELDS.map((f) => <option key={f} value={f}>{f}</option>)}
        </select>
        <select
          aria-label="Operator"
          value={form.operator}
          onChange={(e) => setForm({ ...form, operator: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        >
          {RULE_OPERATORS.map((o) => <option key={o} value={o}>{o}</option>)}
        </select>
        <input
          required
          placeholder="Comparison value"
          value={form.comparisonValue}
          onChange={(e) => setForm({ ...form, comparisonValue: e.target.value })}
          className="md:col-span-2 bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        />
        <select
          aria-label="Action type"
          value={form.actionType}
          onChange={(e) => setForm({ ...form, actionType: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        >
          {RULE_ACTION_TYPES.map((a) => <option key={a} value={a}>{a}</option>)}
        </select>
        <input
          required={form.actionType === 'ASSIGN_CATEGORY'}
          placeholder={form.actionType === 'ASSIGN_CATEGORY' ? 'Category name' : 'Action value (optional)'}
          value={form.actionValue ?? ''}
          onChange={(e) => setForm({ ...form, actionValue: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        />
        <input
          type="number"
          placeholder="Priority"
          value={form.priority ?? 100}
          onChange={(e) => setForm({ ...form, priority: Number(e.target.value) })}
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

export function RuleRow({ userId, rule }: { userId: string; rule: RuleDto }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [confirmDelete, setConfirmDelete] = useState(false);

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['admin-user-rules', userId] });
  }

  const updateMutation = useMutation({
    mutationFn: (values: CreateRuleRequest | UpdateRuleRequest) => adminUserRulesApi.update(userId, rule.id, values),
    onSuccess: () => {
      setEditing(false);
      setError(null);
      invalidate();
    },
    onError: (err: any) => setError(errorMessage(err, 'Failed to update this rule.')),
  });
  const toggleEnabledMutation = useMutation({
    mutationFn: (enabled: boolean) => adminUserRulesApi.update(userId, rule.id, { enabled }),
    onSuccess: invalidate,
  });
  const deleteMutation = useMutation({
    mutationFn: () => adminUserRulesApi.delete(userId, rule.id),
    onSuccess: invalidate,
  });

  if (editing) {
    return (
      <div className="py-2.5 border-b border-border last:border-b-0">
        <InlineRuleForm
          initial={{
            field: rule.field,
            operator: rule.operator,
            comparisonValue: rule.comparisonValue,
            actionType: rule.actionType,
            actionValue: rule.actionValue ?? '',
            priority: rule.priority,
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
        <p className="text-ink font-medium truncate">
          {rule.field} {rule.operator.replace('_', ' ').toLowerCase()} "{rule.comparisonValue}"
        </p>
        <p className="text-xs text-muted">
          {rule.actionType}{rule.actionValue ? `: ${rule.actionValue}` : ''} · priority {rule.priority} · {rule.matchCount} matches
        </p>
        {error && <p className="text-xs text-danger mt-1">{error}</p>}
      </div>
      <div className="flex items-center gap-1.5 flex-shrink-0">
        <button
          type="button"
          disabled={toggleEnabledMutation.isPending}
          onClick={() => toggleEnabledMutation.mutate(!rule.enabled)}
          className={`text-xs font-semibold rounded-full px-2.5 py-1 ${
            rule.enabled ? 'bg-success-bg text-success' : 'bg-bg text-muted border border-border'
          }`}
        >
          {rule.enabled ? 'Enabled' : 'Disabled'}
        </button>
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

      {confirmDelete && (
        <ConfirmDialog
          title="Delete this rule?"
          message="This can't be undone."
          confirmLabel="Delete"
          danger
          onConfirm={() => { setConfirmDelete(false); deleteMutation.mutate(); }}
          onCancel={() => setConfirmDelete(false)}
        />
      )}
    </div>
  );
}

/** Per-user rule management, admin-proxy version of the self-service rule authoring the User
 *  Portal used to expose directly (see the architecture doc on why Rules moved off the main nav
 *  -- this restores the same create/edit/delete capability for support staff acting on a
 *  specific account, reusing RuleService's exact USER-scope logic via AdminUserRuleController). */
export function RulesSection({ userId }: { userId: string }) {
  const [showCreate, setShowCreate] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  const queryClient = useQueryClient();

  const { data: rules, isLoading } = useQuery({
    queryKey: ['admin-user-rules', userId],
    queryFn: () => adminUserRulesApi.list(userId),
  });

  const createMutation = useMutation({
    mutationFn: (values: CreateRuleRequest) => adminUserRulesApi.create(userId, values),
    onSuccess: () => {
      setShowCreate(false);
      setCreateError(null);
      void queryClient.invalidateQueries({ queryKey: ['admin-user-rules', userId] });
    },
    onError: (err: any) => setCreateError(errorMessage(err, 'Failed to create rule.')),
  });

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center justify-between mb-3">
        <div className="flex items-center gap-2">
          <ListFilter size={15} className="text-primary" />
          <h3 className="text-sm font-semibold text-ink">Rules</h3>
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
            <Plus size={13} /> New rule
          </button>
        )}
      </div>
      {showCreate && (
        <div className="mb-3">
          <InlineRuleForm
            initial={BLANK_RULE_FORM}
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
      {!isLoading && (rules ?? []).length === 0 && (
        <p className="text-sm text-muted">No personal rules for this user yet.</p>
      )}
      <div>
        {rules?.map((r) => <RuleRow key={r.id} userId={userId} rule={r} />)}
      </div>
    </div>
  );
}

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
