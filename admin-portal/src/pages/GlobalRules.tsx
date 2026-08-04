import { useId, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ListFilter, Plus, Pencil, Trash2, FlaskConical, Check, X as XIcon } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { FormPanel } from '../components/FormPanel';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { useNotify } from '../context/NotificationContext';
import { adminRulesApi } from '../api/endpoints';
import type { CreateRuleRequest, RuleDto } from '../types';

const FIELDS = ['DESCRIPTION', 'AMOUNT', 'MERCHANT', 'ACCOUNT_TYPE'];
const OPERATORS = ['CONTAINS', 'EQUALS', 'STARTS_WITH', 'GT', 'LT', 'BETWEEN'];
const ACTION_TYPES = ['ASSIGN_CATEGORY', 'MARK_TRANSFER', 'MARK_INVESTMENT', 'MARK_SUBSCRIPTION', 'ADD_TAG'];

const BLANK_FORM: CreateRuleRequest = {
  field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: '', actionType: 'ASSIGN_CATEGORY', actionValue: '', priority: 100,
};

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

const BLANK_TEST_SAMPLE = { sampleDescription: '', sampleAmount: '', sampleMerchant: '', sampleAccountType: '' };

/** Lets an admin check "would this rule match?" against sample transaction fields before saving
 *  it -- reuses AdminRuleController's POST /admin/rules/test, which evaluates the exact same
 *  RuleEngineService.matches() logic a real transaction goes through, without persisting
 *  anything. Lives inside RuleForm (below) so it always tests whatever's currently typed into
 *  field/operator/comparisonValue, saved or not. */
function TestRulePanel({ field, operator, comparisonValue }: { field: string; operator: string; comparisonValue: string }) {
  const [sample, setSample] = useState(BLANK_TEST_SAMPLE);

  const testMutation = useMutation({
    mutationFn: () => adminRulesApi.test({
      field,
      operator,
      comparisonValue,
      sampleDescription: sample.sampleDescription || undefined,
      sampleAmount: sample.sampleAmount ? Number(sample.sampleAmount) : undefined,
      sampleMerchant: sample.sampleMerchant || undefined,
      sampleAccountType: sample.sampleAccountType || undefined,
    }),
  });

  return (
    <div className="bg-bg border border-border rounded-lg p-3.5">
      <div className="flex items-center gap-1.5 mb-2.5">
        <FlaskConical size={13} className="text-primary" />
        <h4 className="text-xs font-semibold text-ink">Test this rule</h4>
      </div>
      <div className="grid gap-2 md:grid-cols-2">
        <input
          placeholder="Sample description"
          value={sample.sampleDescription}
          onChange={(e) => setSample({ ...sample, sampleDescription: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        />
        <input
          placeholder="Sample merchant"
          value={sample.sampleMerchant}
          onChange={(e) => setSample({ ...sample, sampleMerchant: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        />
        <input
          type="number"
          placeholder="Sample amount"
          value={sample.sampleAmount}
          onChange={(e) => setSample({ ...sample, sampleAmount: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        />
        <input
          placeholder="Sample account type (e.g. SAVINGS)"
          value={sample.sampleAccountType}
          onChange={(e) => setSample({ ...sample, sampleAccountType: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        />
      </div>
      <div className="flex items-center gap-2.5 mt-2.5">
        <button
          type="button"
          disabled={!comparisonValue.trim() || testMutation.isPending}
          onClick={() => testMutation.mutate()}
          className="text-xs font-semibold text-primary bg-card border border-border hover:bg-white rounded-lg px-3 py-1.5 disabled:opacity-50"
        >
          {testMutation.isPending ? 'Testing…' : 'Test match'}
        </button>
        {testMutation.data && (
          testMutation.data.matches ? (
            <span className="inline-flex items-center gap-1 text-xs font-semibold text-success"><Check size={13} /> Matches</span>
          ) : (
            <span className="inline-flex items-center gap-1 text-xs font-semibold text-muted"><XIcon size={13} /> No match</span>
          )
        )}
        {testMutation.isError && (
          <span className="text-xs text-danger">{errorMessage(testMutation.error, 'Could not run the test.')}</span>
        )}
      </div>
    </div>
  );
}

/** Create/edit form for a single GLOBAL-scope rule -- applies to every user's auto-categorization,
 *  not just one account's (see RuleService's class comment on why this is a separate admin path
 *  from the self-service USER-scope rules every account already manages for itself). */
function RuleForm({
  initial, editing, onCancel, onSubmit, submitting, error,
}: {
  initial: CreateRuleRequest;
  editing: boolean;
  onCancel: () => void;
  onSubmit: (values: CreateRuleRequest) => void;
  submitting: boolean;
  error: string | null;
}) {
  const [form, setForm] = useState<CreateRuleRequest>(initial);
  // Bug fix: every label below was an unassociated sibling of its input/select, not linked via
  // htmlFor/id -- a real axe "label" violation, same class fixed on Login.tsx.
  const id = useId();

  return (
    <FormPanel
      title={editing ? 'Edit global rule' : 'New global rule'}
      onCancel={onCancel}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(form);
      }}
      error={error}
      submitting={submitting}
      submitLabel={editing ? 'Save changes' : 'Create rule'}
    >
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <label htmlFor={`${id}-field`} className="text-xs font-medium text-muted mb-1 block">Field</label>
          <select
            id={`${id}-field`}
            value={form.field}
            onChange={(e) => setForm({ ...form, field: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          >
            {FIELDS.map((f) => <option key={f} value={f}>{f}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor={`${id}-operator`} className="text-xs font-medium text-muted mb-1 block">Operator</label>
          <select
            id={`${id}-operator`}
            value={form.operator}
            onChange={(e) => setForm({ ...form, operator: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          >
            {OPERATORS.map((o) => <option key={o} value={o}>{o}</option>)}
          </select>
        </div>
        <div className="md:col-span-2">
          <label htmlFor={`${id}-comparisonValue`} className="text-xs font-medium text-muted mb-1 block">Comparison value</label>
          <input
            id={`${id}-comparisonValue`}
            required
            placeholder="e.g. Swiggy"
            value={form.comparisonValue}
            onChange={(e) => setForm({ ...form, comparisonValue: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-actionType`} className="text-xs font-medium text-muted mb-1 block">Action type</label>
          <select
            id={`${id}-actionType`}
            value={form.actionType}
            onChange={(e) => setForm({ ...form, actionType: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          >
            {ACTION_TYPES.map((a) => <option key={a} value={a}>{a}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor={`${id}-actionValue`} className="text-xs font-medium text-muted mb-1 block">
            Action value {form.actionType === 'ASSIGN_CATEGORY' && '(category name, required)'}
          </label>
          <input
            id={`${id}-actionValue`}
            required={form.actionType === 'ASSIGN_CATEGORY'}
            placeholder={form.actionType === 'ASSIGN_CATEGORY' ? 'e.g. Dining' : 'optional'}
            value={form.actionValue ?? ''}
            onChange={(e) => setForm({ ...form, actionValue: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-priority`} className="text-xs font-medium text-muted mb-1 block">Priority</label>
          <input
            id={`${id}-priority`}
            type="number"
            value={form.priority ?? 100}
            onChange={(e) => setForm({ ...form, priority: Number(e.target.value) })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
          <p className="text-[11px] text-muted mt-1">Lower runs first.</p>
        </div>
      </div>

      <TestRulePanel field={form.field} operator={form.operator} comparisonValue={form.comparisonValue} />
    </FormPanel>
  );
}

function GlobalRulesContent() {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const [showCreate, setShowCreate] = useState(false);
  const [editing, setEditing] = useState<RuleDto | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: rules, isLoading } = useQuery({
    queryKey: ['admin-rules'],
    queryFn: () => adminRulesApi.list(),
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['admin-rules'] });
  }

  const createMutation = useMutation({
    mutationFn: (values: CreateRuleRequest) => adminRulesApi.create(values),
    onSuccess: () => {
      setShowCreate(false);
      setFormError(null);
      invalidate();
      notify.success('Rule created.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to create rule.');
      setFormError(msg);
      notify.error(msg);
    },
  });
  const updateMutation = useMutation({
    mutationFn: ({ id, values }: { id: string; values: CreateRuleRequest }) => adminRulesApi.update(id, values),
    onSuccess: () => {
      setEditing(null);
      setFormError(null);
      invalidate();
      notify.success('Rule updated.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to update rule.');
      setFormError(msg);
      notify.error(msg);
    },
  });
  const toggleEnabledMutation = useMutation({
    mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => adminRulesApi.update(id, { enabled }),
    onSuccess: invalidate,
  });
  const deleteMutation = useMutation({
    mutationFn: (id: string) => adminRulesApi.delete(id),
    onSuccess: () => {
      invalidate();
      notify.success('Rule deleted.');
    },
    onError: (err: any) => notify.error(errorMessage(err, 'Failed to delete this rule.')),
  });

  const columns: DataTableColumn<RuleDto>[] = [
    {
      header: 'Rule',
      render: (rule) => (
        <div className="flex items-center gap-2">
          <ListFilter size={13} className="text-primary flex-shrink-0" />
          <span className="text-ink">
            {rule.field} {rule.operator.replace('_', ' ').toLowerCase()} "{rule.comparisonValue}"
          </span>
        </div>
      ),
    },
    {
      header: 'Action',
      cellClassName: 'text-muted',
      render: (rule) => `${rule.actionType}${rule.actionValue ? `: ${rule.actionValue}` : ''}`,
    },
    { header: 'Priority', render: (rule) => rule.priority, cellClassName: 'text-muted' },
    { header: 'Matches', render: (rule) => rule.matchCount, cellClassName: 'text-muted' },
    {
      header: 'Status',
      render: (rule) => (
        <button
          type="button"
          disabled={toggleEnabledMutation.isPending}
          onClick={() => toggleEnabledMutation.mutate({ id: rule.id, enabled: !rule.enabled })}
          className={`text-xs font-semibold rounded-full px-2.5 py-1 ${
            rule.enabled ? 'bg-success-bg text-success' : 'bg-bg text-muted border border-border'
          }`}
        >
          {rule.enabled ? 'Enabled' : 'Disabled'}
        </button>
      ),
    },
    {
      header: 'Actions',
      headerClassName: 'text-right',
      cellClassName: 'text-right',
      render: (rule) => (
        <div className="inline-flex items-center gap-1">
          <button
            type="button"
            title="Edit"
            onClick={() => {
              setEditing(rule);
              setFormError(null);
            }}
            className="w-8 h-8 rounded-lg hover:bg-bg text-muted hover:text-ink inline-flex items-center justify-center"
          >
            <Pencil size={14} />
          </button>
          <button
            type="button"
            title="Delete"
            disabled={deleteMutation.isPending}
            onClick={() => {
              if (confirm('Delete this global rule? It stops applying to every user immediately.')) {
                deleteMutation.mutate(rule.id);
              }
            }}
            className="w-8 h-8 rounded-lg hover:bg-danger-bg text-muted hover:text-danger inline-flex items-center justify-center"
          >
            <Trash2 size={14} />
          </button>
        </div>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted max-w-xl">
          Global rules run auto-categorization for every user, in priority order, before each
          account's own personal rules. Use sparingly -- these affect everyone.
        </p>
        {!showCreate && (
          <button
            type="button"
            onClick={() => {
              setShowCreate(true);
              setFormError(null);
            }}
            className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-white text-sm font-semibold rounded-lg px-4 py-2.5 flex-shrink-0"
          >
            <Plus size={15} /> New rule
          </button>
        )}
      </div>

      {showCreate && (
        <RuleForm
          initial={BLANK_FORM}
          editing={false}
          submitting={createMutation.isPending}
          error={formError}
          onCancel={() => {
            setShowCreate(false);
            setFormError(null);
          }}
          onSubmit={(values) => createMutation.mutate(values)}
        />
      )}

      {editing && (
        <RuleForm
          initial={{
            field: editing.field,
            operator: editing.operator,
            comparisonValue: editing.comparisonValue,
            actionType: editing.actionType,
            actionValue: editing.actionValue ?? '',
            priority: editing.priority,
          }}
          editing
          submitting={updateMutation.isPending}
          error={formError}
          onCancel={() => {
            setEditing(null);
            setFormError(null);
          }}
          onSubmit={(values) => updateMutation.mutate({ id: editing.id, values })}
        />
      )}

      <DataTable
        columns={columns}
        rows={rules}
        keyFor={(rule) => rule.id}
        loading={isLoading}
        emptyMessage="No global rules yet."
      />
    </div>
  );
}

export default function GlobalRules() {
  return (
    <AdminLayout title="Global Rules" subtitle="Auto-categorization rules that apply to every user">
      <RequirePermission permission="RULE_MANAGE">
        <GlobalRulesContent />
      </RequirePermission>
    </AdminLayout>
  );
}
