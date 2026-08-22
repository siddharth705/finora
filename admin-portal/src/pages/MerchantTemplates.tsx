import { useId, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FileCode, Plus, Pencil, Power, FlaskConical, Check, X as XIcon, ShieldAlert } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { FormPanel } from '../components/FormPanel';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { useNotify } from '../context/NotificationContext';
import { adminMerchantTemplatesApi } from '../api/endpoints';
import type { CreateMerchantTemplateRequest, MerchantTemplateDto, TestMerchantTemplateResult } from '../types';

const BLANK_FORM: CreateMerchantTemplateRequest = {
  merchantDomain: '', merchantName: '', receiptMarker: '', amountPattern: '', datePattern: '',
};

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

/** The three fields a test result actually covers -- used to tell "this was tested" apart from
 *  "this WAS tested, before the admin changed something since." merchantDomain is deliberately
 *  excluded: it doesn't affect matching (see TestMerchantTemplateRequest's own doc comment) and,
 *  on the create form, is the one field a test can legitimately run before it's finalized. */
type TestedFields = { receiptMarker: string; amountPattern: string; datePattern: string };

/**
 * Lets an admin check "would this template match?" against a pasted sample email before it can go
 * live -- reuses AdminMerchantTemplateController's POST /admin/merchant-templates/test, which runs
 * the exact same TemplateEmailParser.parse() logic a real Gmail receipt goes through, without
 * persisting anything. Lives inside TemplateForm (below) so it always tests whatever's currently
 * typed, saved or not -- same shape as GlobalRules.tsx's TestRulePanel for the Rule Engine module.
 *
 * Reports every completed test up to the parent via onResult, keyed by exactly which field values
 * it ran against -- that's what lets the parent's Activate button require a PASSED test against
 * the CURRENT fields, not just any test that happened to run at some point during editing.
 */
function TestTemplatePanel({
  merchantDomain, receiptMarker, amountPattern, datePattern, onResult,
}: TestedFields & { merchantDomain: string; onResult: (result: TestMerchantTemplateResult, testedFor: TestedFields) => void }) {
  const [sampleHtml, setSampleHtml] = useState('');

  // mutationFn takes the tested values as its argument (captured in the onClick handler below,
  // at the moment "Test template" is actually clicked) rather than closing over the
  // receiptMarker/amountPattern/datePattern props directly. That distinction matters: if the
  // admin edits a field while the request is still in flight, a closure over the live props would
  // read whatever is CURRENTLY in the form when the response arrives, not what was actually sent
  // -- silently attributing a pass to an untested value and defeating the one thing this panel
  // exists to guarantee. onSuccess's second argument is TanStack Query's own `variables` -- the
  // exact object passed to mutate() -- which stays fixed regardless of later renders, unlike a
  // value read from props inside the callback.
  const testMutation = useMutation({
    mutationFn: (vars: TestedFields & { merchantDomain: string; sampleHtml: string }) =>
      adminMerchantTemplatesApi.test(vars),
    onSuccess: (result, vars) => onResult(result, {
      receiptMarker: vars.receiptMarker, amountPattern: vars.amountPattern, datePattern: vars.datePattern,
    }),
  });

  // merchantDomain is required here too, even though it doesn't affect matching itself -- the
  // backend's TestTemplateRequest requires it non-blank because it flows into
  // ParsedReceipt.merchantDomain() on a PARSED result, and that record's own constructor rejects
  // a blank value. Matching the backend's requirement client-side avoids a confusing 400 on an
  // otherwise-correct test.
  const canTest = merchantDomain.trim() && receiptMarker.trim() && amountPattern.trim()
      && datePattern.trim() && sampleHtml.trim();

  return (
    <div className="bg-bg border border-border rounded-lg p-3.5">
      <div className="flex items-center gap-1.5 mb-2.5">
        <FlaskConical size={13} className="text-primary" />
        <h4 className="text-xs font-semibold text-ink">Test against a sample email</h4>
      </div>
      <textarea
        placeholder="Paste the sample email's HTML (or plain text) here"
        value={sampleHtml}
        onChange={(e) => setSampleHtml(e.target.value)}
        rows={6}
        className="w-full bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs font-mono"
      />
      <div className="flex items-center gap-2.5 mt-2.5 flex-wrap">
        <button
          type="button"
          disabled={!canTest || testMutation.isPending}
          onClick={() => testMutation.mutate({ merchantDomain, receiptMarker, amountPattern, datePattern, sampleHtml })}
          className="text-xs font-semibold text-primary bg-card border border-border hover:bg-white rounded-lg px-3 py-1.5 disabled:opacity-50"
        >
          {testMutation.isPending ? 'Testing…' : 'Test template'}
        </button>
        {testMutation.data && (
          testMutation.data.status === 'PARSED' ? (
            <span className="inline-flex items-center gap-1 text-xs font-semibold text-success">
              <Check size={13} /> Parsed -- amount {testMutation.data.amount}, date {testMutation.data.transactionDate}
            </span>
          ) : (
            <span className="inline-flex items-center gap-1 text-xs font-semibold text-danger">
              <XIcon size={13} /> {testMutation.data.status === 'NOT_A_RECEIPT' ? 'Not a receipt' : 'Malformed'}
              {testMutation.data.reason ? ` -- ${testMutation.data.reason}` : ''}
            </span>
          )
        )}
        {testMutation.isError && (
          <span className="text-xs text-danger">{errorMessage(testMutation.error, 'Could not run the test.')}</span>
        )}
      </div>
      {testMutation.data?.status === 'PARSED' && testMutation.data.violations.length > 0 && (
        <div className="mt-2 text-xs text-danger bg-danger-bg rounded-lg px-2.5 py-1.5">
          <p className="font-semibold flex items-center gap-1"><ShieldAlert size={13} /> Parsed, but implausible:</p>
          <ul className="list-disc list-inside mt-1">
            {testMutation.data.violations.map((v) => <li key={v.field}>{v.field}: {v.reason}</li>)}
          </ul>
        </div>
      )}
    </div>
  );
}

/** Create/edit form for one MerchantTemplate. The domain field is only shown (and only submitted)
 *  when creating -- editing an existing template cannot change its domain, matching
 *  TrustedSenderDomainService.rename's own "the domain is immutable" reasoning (see
 *  MerchantTemplateAdminService's class doc for why this table needs the reverse of that: pattern
 *  fields DO stay editable in place, unlike a trusted-domain row's label-only rename).
 *
 *  Activate only appears when editing an existing, currently-disabled template, and only becomes
 *  clickable once a test has PASSED against the exact field values currently in the form -- the
 *  client-side half of "must not be a blind CRUD screen" (the server-side half is that a brand
 *  new template is always created disabled regardless of what's sent, and an edit to a live
 *  template's matching fields auto-disables it). */
function TemplateForm({
  initial, editingTemplate, onCancel, onSubmit, submitting, error, onActivate, onDeactivate, activating,
}: {
  initial: CreateMerchantTemplateRequest;
  editingTemplate: MerchantTemplateDto | null;
  onCancel: () => void;
  onSubmit: (values: CreateMerchantTemplateRequest) => void;
  submitting: boolean;
  error: string | null;
  onActivate: () => void;
  onDeactivate: () => void;
  activating: boolean;
}) {
  const [form, setForm] = useState<CreateMerchantTemplateRequest>(initial);
  const [lastPassedTest, setLastPassedTest] = useState<TestedFields | null>(null);
  const id = useId();

  const currentFields: TestedFields = {
    receiptMarker: form.receiptMarker, amountPattern: form.amountPattern, datePattern: form.datePattern,
  };
  const canActivate = lastPassedTest !== null
      && lastPassedTest.receiptMarker === currentFields.receiptMarker
      && lastPassedTest.amountPattern === currentFields.amountPattern
      && lastPassedTest.datePattern === currentFields.datePattern;

  return (
    <FormPanel
      title={editingTemplate ? `Edit ${editingTemplate.merchantDomain}` : 'New merchant template'}
      onCancel={onCancel}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(form);
      }}
      error={error}
      submitting={submitting}
      submitLabel={editingTemplate ? 'Save changes' : 'Create template'}
    >
      <div className="grid gap-3 md:grid-cols-2">
        {!editingTemplate && (
          <div className="md:col-span-2">
            <label htmlFor={`${id}-domain`} className="text-xs font-medium text-muted mb-1 block">
              Merchant domain
            </label>
            <input
              id={`${id}-domain`}
              required
              placeholder="e.g. swiggy.com"
              value={form.merchantDomain}
              onChange={(e) => setForm({ ...form, merchantDomain: e.target.value })}
              className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
            />
            <p className="text-[11px] text-muted mt-1">
              Cannot be changed after creation. Must be in the trusted-sender registry for this
              template to ever run in production.
            </p>
          </div>
        )}
        <div className={editingTemplate ? 'md:col-span-2' : ''}>
          <label htmlFor={`${id}-name`} className="text-xs font-medium text-muted mb-1 block">Merchant name</label>
          <input
            id={`${id}-name`}
            required
            placeholder="e.g. Swiggy"
            value={form.merchantName}
            onChange={(e) => setForm({ ...form, merchantName: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div className="md:col-span-2">
          <label htmlFor={`${id}-marker`} className="text-xs font-medium text-muted mb-1 block">Receipt marker</label>
          <input
            id={`${id}-marker`}
            required
            placeholder="A literal phrase every receipt from this merchant contains, e.g. Order Summary"
            value={form.receiptMarker}
            onChange={(e) => setForm({ ...form, receiptMarker: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-amount`} className="text-xs font-medium text-muted mb-1 block">Amount pattern</label>
          <input
            id={`${id}-amount`}
            required
            placeholder="e.g. Grand Total: Rs. {amount}"
            value={form.amountPattern}
            onChange={(e) => setForm({ ...form, amountPattern: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm font-mono"
          />
        </div>
        <div>
          <label htmlFor={`${id}-date`} className="text-xs font-medium text-muted mb-1 block">Date pattern</label>
          <input
            id={`${id}-date`}
            required
            placeholder="e.g. Order Date: {date}"
            value={form.datePattern}
            onChange={(e) => setForm({ ...form, datePattern: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm font-mono"
          />
        </div>
        <p className="md:col-span-2 text-[11px] text-muted">
          Exactly one <code>{'{amount}'}</code> / <code>{'{date}'}</code> placeholder each --
          everything else is matched as literal text, copied straight out of a real email.
        </p>
      </div>

      <TestTemplatePanel
        merchantDomain={editingTemplate?.merchantDomain ?? form.merchantDomain}
        receiptMarker={form.receiptMarker}
        amountPattern={form.amountPattern}
        datePattern={form.datePattern}
        onResult={(result, testedFor) => setLastPassedTest(result.status === 'PARSED' ? testedFor : null)}
      />

      {editingTemplate && (
        <div className="flex items-center gap-2.5 pt-1">
          {editingTemplate.enabled ? (
            <button
              type="button"
              disabled={activating}
              onClick={onDeactivate}
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-muted bg-bg border border-border hover:bg-white rounded-lg px-3 py-1.5"
            >
              <Power size={13} /> Deactivate
            </button>
          ) : (
            <button
              type="button"
              disabled={!canActivate || activating}
              title={canActivate ? undefined : 'Run a passing test against these exact field values first'}
              onClick={onActivate}
              className="inline-flex items-center gap-1.5 text-xs font-semibold text-on-primary bg-primary hover:bg-primary-dark rounded-lg px-3 py-1.5 disabled:opacity-50"
            >
              <Power size={13} /> Activate
            </button>
          )}
          {!editingTemplate.enabled && !canActivate && (
            <span className="text-[11px] text-muted">A passing test against these exact fields is required first.</span>
          )}
          {!editingTemplate.domainIsTrusted && (
            <span className="inline-flex items-center gap-1 text-[11px] text-danger">
              <ShieldAlert size={12} /> {editingTemplate.merchantDomain} isn't in the trusted-sender registry yet -- this won't run in production until it is.
            </span>
          )}
        </div>
      )}
    </FormPanel>
  );
}

function MerchantTemplatesContent() {
  const queryClient = useQueryClient();
  const notify = useNotify();
  const [showCreate, setShowCreate] = useState(false);
  const [editing, setEditing] = useState<MerchantTemplateDto | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  const { data: templates, isLoading } = useQuery({
    queryKey: ['admin-merchant-templates'],
    queryFn: () => adminMerchantTemplatesApi.list(),
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['admin-merchant-templates'] });
  }

  const createMutation = useMutation({
    mutationFn: (values: CreateMerchantTemplateRequest) => adminMerchantTemplatesApi.create(values),
    onSuccess: () => {
      setShowCreate(false);
      setFormError(null);
      invalidate();
      notify.success('Template created, disabled pending a successful test.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to create template.');
      setFormError(msg);
      notify.error(msg);
    },
  });
  const updateMutation = useMutation({
    mutationFn: ({ id, values }: { id: string; values: CreateMerchantTemplateRequest }) =>
      adminMerchantTemplatesApi.update(id, values),
    onSuccess: (result) => {
      setEditing(result);
      setFormError(null);
      invalidate();
      notify.success(result.enabled ? 'Template updated.' : 'Template updated and disabled pending re-test.');
    },
    onError: (err: any) => {
      const msg = errorMessage(err, 'Failed to update template.');
      setFormError(msg);
      notify.error(msg);
    },
  });
  const activateMutation = useMutation({
    mutationFn: (id: string) => adminMerchantTemplatesApi.activate(id),
    onSuccess: (result) => {
      setEditing(result);
      invalidate();
      notify.success('Template activated.');
    },
    onError: (err: any) => notify.error(errorMessage(err, 'Failed to activate this template.')),
  });
  const deactivateMutation = useMutation({
    mutationFn: (id: string) => adminMerchantTemplatesApi.deactivate(id),
    onSuccess: (result) => {
      setEditing(result);
      invalidate();
      notify.success('Template deactivated.');
    },
    onError: (err: any) => notify.error(errorMessage(err, 'Failed to deactivate this template.')),
  });

  const columns: DataTableColumn<MerchantTemplateDto>[] = [
    {
      header: 'Merchant',
      render: (t) => (
        <div className="flex items-center gap-2">
          <FileCode size={13} className="text-primary flex-shrink-0" />
          <div>
            <span className="text-ink">{t.merchantName}</span>
            <span className="text-muted"> -- {t.merchantDomain}</span>
          </div>
        </div>
      ),
    },
    {
      header: 'Trusted',
      render: (t) => t.domainIsTrusted
        ? <span className="text-xs text-muted">Yes</span>
        : <span className="inline-flex items-center gap-1 text-xs text-danger"><ShieldAlert size={12} /> No</span>,
    },
    {
      header: 'Status',
      render: (t) => (
        <span className={`text-xs font-semibold rounded-full px-2.5 py-1 ${
          t.enabled ? 'bg-success-bg text-success' : 'bg-bg text-muted border border-border'
        }`}>
          {t.enabled ? 'Active' : 'Disabled'}
        </span>
      ),
    },
    {
      header: 'Actions',
      headerClassName: 'text-right',
      cellClassName: 'text-right',
      render: (t) => (
        <button
          type="button"
          title="Edit / test"
          onClick={() => {
            setEditing(t);
            setFormError(null);
          }}
          className="w-8 h-8 rounded-lg hover:bg-bg text-muted hover:text-ink inline-flex items-center justify-center"
        >
          <Pencil size={14} />
        </button>
      ),
    },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted max-w-2xl">
          Declarative Gmail receipt parsers -- for merchants with a single amount, single date, and
          a stable email format. A new template goes live only after it's tested against a real
          sample here. Merchants needing conditional logic (refunds, multiple amounts) still need a
          hand-written parser and an engineering release.
        </p>
        {!showCreate && (
          <button
            type="button"
            onClick={() => {
              setShowCreate(true);
              setFormError(null);
            }}
            className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2.5 flex-shrink-0"
          >
            <Plus size={15} /> New template
          </button>
        )}
      </div>

      {showCreate && (
        <TemplateForm
          initial={BLANK_FORM}
          editingTemplate={null}
          submitting={createMutation.isPending}
          error={formError}
          onCancel={() => {
            setShowCreate(false);
            setFormError(null);
          }}
          onSubmit={(values) => createMutation.mutate(values)}
          onActivate={() => {}}
          onDeactivate={() => {}}
          activating={false}
        />
      )}

      {editing && (
        <TemplateForm
          initial={{
            merchantDomain: editing.merchantDomain,
            merchantName: editing.merchantName,
            receiptMarker: editing.receiptMarker,
            amountPattern: editing.amountPattern,
            datePattern: editing.datePattern,
          }}
          editingTemplate={editing}
          submitting={updateMutation.isPending}
          error={formError}
          onCancel={() => {
            setEditing(null);
            setFormError(null);
          }}
          onSubmit={(values) => updateMutation.mutate({ id: editing.id, values })}
          onActivate={() => activateMutation.mutate(editing.id)}
          onDeactivate={() => deactivateMutation.mutate(editing.id)}
          activating={activateMutation.isPending || deactivateMutation.isPending}
        />
      )}

      <DataTable
        columns={columns}
        rows={templates}
        keyFor={(t) => t.id}
        loading={isLoading}
        emptyMessage="No merchant templates yet."
      />
    </div>
  );
}

export default function MerchantTemplates() {
  return (
    <AdminLayout
      title="Merchant Templates"
      subtitle="Declarative Gmail receipt parsers -- no engineering release needed for a stable, single-amount merchant"
    >
      <RequirePermission permission="MERCHANT_MANAGE">
        <MerchantTemplatesContent />
      </RequirePermission>
    </AdminLayout>
  );
}
