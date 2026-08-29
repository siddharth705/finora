import { useId, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Landmark, Plus, Trash2, Pencil, ExternalLink, Activity } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { FormPanel } from '../components/FormPanel';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { EntityDrawer, type EntityDrawerTab } from '../components/EntityDrawer';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { Pagination } from '../components/Pagination';
import { adminBanksApi } from '../api/endpoints';
import { isSafeHttpUrl } from '../lib/safeUrl';
import type { BankDto, CreateBankRequest, UpdateBankRequest } from '../types';

const BLANK_FORM: CreateBankRequest = {
  id: '', officialName: '', shortName: '', colorHex: '#64748B', initials: '', category: '', websiteUrl: '', ifscPrefix: '',
};

const PAGE_SIZE = 20;

/** Create form for a new custom bank -- unchanged from before Phase 4. Editing an existing bank
 *  now happens inside EntityDrawer's Summary tab (see BankSummaryTab below), not here -- this
 *  component is create-only now, hence no `editing` prop anymore. */
function BankCreateForm({
  onCancel, onSubmit, submitting, error,
}: {
  onCancel: () => void;
  onSubmit: (values: CreateBankRequest) => void;
  submitting: boolean;
  error: string | null;
}) {
  const [form, setForm] = useState<CreateBankRequest>(BLANK_FORM);
  // Bug fix: every label below was an unassociated sibling of its input, not linked via
  // htmlFor/id -- a real axe "label" violation, same class fixed on Login.tsx.
  const id = useId();

  return (
    <FormPanel
      title="Add a custom bank"
      onCancel={onCancel}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(form);
      }}
      error={error}
      submitting={submitting}
      submitLabel="Add bank"
    >
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <label htmlFor={`${id}-id`} className="text-xs font-medium text-muted mb-1 block">Bank ID</label>
          <input
            id={`${id}-id`}
            required
            placeholder="e.g. IOB"
            value={form.id}
            onChange={(e) => setForm({ ...form, id: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-shortName`} className="text-xs font-medium text-muted mb-1 block">Short name</label>
          <input
            id={`${id}-shortName`}
            required
            placeholder="e.g. Indian Overseas Bank"
            value={form.shortName}
            onChange={(e) => setForm({ ...form, shortName: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div className="md:col-span-2">
          <label htmlFor={`${id}-officialName`} className="text-xs font-medium text-muted mb-1 block">Official name</label>
          <input
            id={`${id}-officialName`}
            required
            placeholder="e.g. Indian Overseas Bank Ltd."
            value={form.officialName}
            onChange={(e) => setForm({ ...form, officialName: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-initials`} className="text-xs font-medium text-muted mb-1 block">Initials</label>
          <input
            id={`${id}-initials`}
            maxLength={4}
            placeholder="e.g. HDFC"
            value={form.initials}
            onChange={(e) => setForm({ ...form, initials: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-colorHex`} className="text-xs font-medium text-muted mb-1 block">Color</label>
          <input
            id={`${id}-colorHex`}
            type="color"
            value={form.colorHex || '#64748B'}
            onChange={(e) => setForm({ ...form, colorHex: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg h-9 px-1"
          />
        </div>
        <div>
          <label htmlFor={`${id}-category`} className="text-xs font-medium text-muted mb-1 block">Category</label>
          <input
            id={`${id}-category`}
            placeholder="e.g. PUBLIC_SECTOR"
            value={form.category}
            onChange={(e) => setForm({ ...form, category: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-ifscPrefix`} className="text-xs font-medium text-muted mb-1 block">IFSC prefix</label>
          <input
            id={`${id}-ifscPrefix`}
            placeholder="e.g. IOBA"
            value={form.ifscPrefix}
            onChange={(e) => setForm({ ...form, ifscPrefix: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div className="md:col-span-2">
          <label htmlFor={`${id}-websiteUrl`} className="text-xs font-medium text-muted mb-1 block">Website</label>
          <input
            id={`${id}-websiteUrl`}
            placeholder="https://…"
            value={form.websiteUrl}
            onChange={(e) => setForm({ ...form, websiteUrl: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
      </div>
    </FormPanel>
  );
}

function ReadRow({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <p className="text-xs font-medium text-muted mb-0.5">{label}</p>
      <p className="text-sm text-ink">{value || '—'}</p>
    </div>
  );
}

/**
 * EntityDrawer reference implementation, Summary tab -- read view by default with an inline Edit
 * toggle, same "list → detail → edit in place" flow the drawer pattern replaces the old
 * List→Edit-page flow with. Only the editable business fields live here (officialName, shortName,
 * category, ifscPrefix, websiteUrl) -- id/colorHex/initials/logoPath/supportedAccountTypes are
 * structural, shown on the Metadata tab instead (see BankMetadataTab below).
 */
function BankSummaryTab({ bank, onSave, saving, error }: {
  bank: BankDto;
  onSave: (values: UpdateBankRequest) => void;
  saving: boolean;
  error: string | null;
}) {
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState({
    officialName: bank.officialName,
    shortName: bank.shortName,
    category: bank.category ?? '',
    ifscPrefix: bank.ifscPrefix ?? '',
    websiteUrl: bank.websiteUrl ?? '',
  });
  // Bug fix: every label in the edit form below was an unassociated sibling of its input, not
  // linked via htmlFor/id -- a real axe "label" violation, same class fixed on Login.tsx.
  const id = useId();

  function startEditing() {
    setForm({
      officialName: bank.officialName,
      shortName: bank.shortName,
      category: bank.category ?? '',
      ifscPrefix: bank.ifscPrefix ?? '',
      websiteUrl: bank.websiteUrl ?? '',
    });
    setEditing(true);
  }

  if (!editing) {
    return (
      <div className="space-y-4">
        <div className="flex items-center gap-3">
          <span
            className="w-10 h-10 rounded-lg flex items-center justify-center text-xs font-bold text-white flex-shrink-0"
            style={{ backgroundColor: bank.colorHex || '#64748B' }}
          >
            {bank.initials || <Landmark size={16} />}
          </span>
          <div className="min-w-0">
            <p className="font-semibold text-ink truncate">{bank.shortName}</p>
            <p className="text-xs text-muted truncate">{bank.officialName}</p>
          </div>
        </div>
        <div className="grid grid-cols-2 gap-4">
          <ReadRow label="Category" value={bank.category ?? ''} />
          <ReadRow label="IFSC prefix" value={bank.ifscPrefix ?? ''} />
        </div>
        <div>
          <p className="text-xs font-medium text-muted mb-0.5">Website</p>
          {/* Bug fix / security hardening: bank.websiteUrl had no scheme validation on the
              backend when this was written -- any BANK_MANAGE admin could set it to a
              `javascript:` URL, and this used to render it as a real, clickable <a href> to every
              OTHER admin who opens this bank's drawer. The backend validates now (@SafeHttpUrl on
              AccountDto's create and update requests), so that clause describes history, not the
              current state -- but this check is not therefore redundant. Server-side validation
              bounds what new writes can store; it says nothing about rows written before it
              existed. isSafeHttpUrl restricts what actually becomes a link to http(s); an unsafe
              value still displays (so nothing silently disappears) but as plain text, never as
              something clickable. See lib/safeUrl.ts's own doc comment. */}
          {bank.websiteUrl && isSafeHttpUrl(bank.websiteUrl) ? (
            <a
              href={bank.websiteUrl}
              target="_blank"
              rel="noreferrer"
              className="text-sm text-primary font-medium inline-flex items-center gap-1"
            >
              {bank.websiteUrl} <ExternalLink size={12} />
            </a>
          ) : bank.websiteUrl ? (
            <p className="text-sm text-ink break-all">{bank.websiteUrl}</p>
          ) : (
            <p className="text-sm text-ink">—</p>
          )}
        </div>
        <button
          type="button"
          onClick={startEditing}
          className="inline-flex items-center gap-1.5 text-sm font-semibold text-primary"
        >
          <Pencil size={13} /> Edit details
        </button>
      </div>
    );
  }

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSave(form);
      }}
      className="space-y-3"
    >
      {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2">{error}</p>}
      <div>
        <label htmlFor={`${id}-shortName`} className="text-xs font-medium text-muted mb-1 block">Short name</label>
        <input
          id={`${id}-shortName`}
          required
          value={form.shortName}
          onChange={(e) => setForm({ ...form, shortName: e.target.value })}
          className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
        />
      </div>
      <div>
        <label htmlFor={`${id}-officialName`} className="text-xs font-medium text-muted mb-1 block">Official name</label>
        <input
          id={`${id}-officialName`}
          required
          value={form.officialName}
          onChange={(e) => setForm({ ...form, officialName: e.target.value })}
          className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
        />
      </div>
      <div>
        <label htmlFor={`${id}-category`} className="text-xs font-medium text-muted mb-1 block">Category</label>
        <input
          id={`${id}-category`}
          value={form.category}
          onChange={(e) => setForm({ ...form, category: e.target.value })}
          className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
        />
      </div>
      <div>
        <label htmlFor={`${id}-ifscPrefix`} className="text-xs font-medium text-muted mb-1 block">IFSC prefix</label>
        <input
          id={`${id}-ifscPrefix`}
          value={form.ifscPrefix}
          onChange={(e) => setForm({ ...form, ifscPrefix: e.target.value })}
          className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
        />
      </div>
      <div>
        <label htmlFor={`${id}-websiteUrl`} className="text-xs font-medium text-muted mb-1 block">Website</label>
        <input
          id={`${id}-websiteUrl`}
          value={form.websiteUrl}
          onChange={(e) => setForm({ ...form, websiteUrl: e.target.value })}
          className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
        />
      </div>
      <div className="flex justify-end gap-2 pt-1">
        <button type="button" onClick={() => setEditing(false)} className="text-sm font-medium text-muted px-3.5 py-2 rounded-lg hover:bg-bg">
          Cancel
        </button>
        <button
          type="submit"
          disabled={saving}
          className="bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2 disabled:opacity-50"
        >
          Save changes
        </button>
      </div>
    </form>
  );
}

/** EntityDrawer reference implementation, Metadata tab -- structural fields that don't belong in
 *  the editable Summary view: the immutable id (this bank's natural key/primary key, never
 *  editable once created), and the visual-identity fields (colorHex/initials/logoPath) plus
 *  supportedAccountTypes. No timestamps here -- BankDto genuinely doesn't carry createdAt/
 *  updatedAt (see AccountDto.BankDto on the backend), and this tab shows only what's real. */
function BankMetadataTab({ bank }: { bank: BankDto }) {
  return (
    <div className="space-y-4">
      <ReadRow label="Bank ID" value={bank.id} />
      <div>
        <p className="text-xs font-medium text-muted mb-0.5">Color</p>
        <div className="flex items-center gap-2">
          <span className="w-5 h-5 rounded border border-border" style={{ backgroundColor: bank.colorHex || '#64748B' }} />
          <p className="text-sm text-ink font-mono">{bank.colorHex || '—'}</p>
        </div>
      </div>
      <ReadRow label="Initials" value={bank.initials} />
      <ReadRow label="Logo path" value={bank.logoPath} />
      <div>
        <p className="text-xs font-medium text-muted mb-1">Supported account types</p>
        <div className="flex flex-wrap gap-1.5">
          {bank.supportedAccountTypes.length === 0 && <p className="text-sm text-ink">—</p>}
          {bank.supportedAccountTypes.map((t) => (
            <span key={t} className="text-xs font-medium bg-bg border border-border rounded-full px-2.5 py-1 text-muted">
              {t}
            </span>
          ))}
        </div>
      </div>
    </div>
  );
}

/** EntityDrawer reference implementation, Audit tab -- this bank's real audit trail via
 *  adminBanksApi.audit (AdminBankController.audit / AuditLogRepository.findByBankIdInMetadata).
 *  An empty result is a normal state (built-in banks and banks created before this endpoint
 *  existed genuinely have no recorded history), shown as a plain message, not an error. */
function BankAuditTab({ bankId }: { bankId: string }) {
  const { data, isLoading } = useQuery({
    queryKey: ['admin-bank-audit', bankId],
    queryFn: () => adminBanksApi.audit(bankId),
  });

  if (isLoading) return <p className="text-sm text-muted">Loading…</p>;
  if (!data || data.length === 0) return <p className="text-sm text-muted">No recorded history for this bank.</p>;

  return (
    <div className="space-y-3">
      {data.map((log) => (
        <div key={log.id} className="flex items-start gap-2.5">
          <Activity size={14} className="text-muted flex-shrink-0 mt-0.5" />
          <div className="min-w-0">
            <p className="text-sm font-medium text-ink">{log.action}</p>
            <p className="text-xs text-muted">{new Date(log.createdAt).toLocaleString()}</p>
          </div>
        </div>
      ))}
    </div>
  );
}

function BanksContent() {
  const queryClient = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [selected, setSelected] = useState<BankDto | null>(null);
  const [formError, setFormError] = useState<string | null>(null);
  const [drawerError, setDrawerError] = useState<string | null>(null);
  const [confirmDeleteBank, setConfirmDeleteBank] = useState<BankDto | null>(null);
  // Bumped on every successful save so BankSummaryTab (keyed on selected.id + this) remounts and
  // its internal `editing` state resets to false -- otherwise a save would leave the tab sitting
  // in edit mode even though the drawer is now showing the freshly-updated read view underneath.
  const [saveVersion, setSaveVersion] = useState(0);
  const [page, setPage] = useState(0);

  const { data: banks, isLoading } = useQuery({
    queryKey: ['admin-banks', page],
    queryFn: () => adminBanksApi.list(page, PAGE_SIZE),
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['admin-banks'] });
  }

  const createMutation = useMutation({
    mutationFn: (values: CreateBankRequest) => adminBanksApi.create(values),
    onSuccess: () => {
      setShowCreate(false);
      setFormError(null);
      invalidate();
    },
    onError: (err: any) => setFormError(err?.response?.data?.message ?? 'Failed to add bank.'),
  });
  const updateMutation = useMutation({
    mutationFn: ({ id, values }: { id: string; values: UpdateBankRequest }) => adminBanksApi.update(id, values),
    onSuccess: (updated) => {
      setSelected(updated);
      setDrawerError(null);
      setSaveVersion((v) => v + 1);
      invalidate();
    },
    onError: (err: any) => setDrawerError(err?.response?.data?.message ?? 'Failed to update bank.'),
  });
  const deleteMutation = useMutation({
    mutationFn: (id: string) => adminBanksApi.delete(id),
    onSuccess: () => {
      setSelected(null);
      invalidate();
    },
  });

  const columns: DataTableColumn<BankDto>[] = [
    {
      header: 'Bank',
      render: (bank) => (
        <button
          type="button"
          onClick={() => {
            setSelected(bank);
            setDrawerError(null);
          }}
          className="flex items-center gap-2.5 text-left"
        >
          <span
            className="w-7 h-7 rounded-lg flex items-center justify-center text-[10px] font-bold text-white flex-shrink-0"
            style={{ backgroundColor: bank.colorHex || '#64748B' }}
          >
            {bank.initials || <Landmark size={13} />}
          </span>
          <div>
            <p className="font-medium text-ink hover:text-primary">{bank.shortName}</p>
            <p className="text-xs text-muted">{bank.officialName}</p>
          </div>
        </button>
      ),
    },
    { header: 'ID', render: (bank) => bank.id, cellClassName: 'text-muted font-mono text-xs' },
    { header: 'Category', render: (bank) => bank.category ?? '—', cellClassName: 'text-muted' },
    { header: 'IFSC prefix', render: (bank) => bank.ifscPrefix ?? '—', cellClassName: 'text-muted font-mono text-xs' },
    {
      header: 'Actions',
      headerClassName: 'text-right',
      cellClassName: 'text-right',
      render: (bank) => (
        <div className="inline-flex items-center gap-1">
          <button
            type="button"
            title="Delete"
            disabled={deleteMutation.isPending}
            onClick={() => setConfirmDeleteBank(bank)}
            className="w-8 h-8 rounded-lg hover:bg-danger-bg text-muted hover:text-danger inline-flex items-center justify-center"
          >
            <Trash2 size={14} />
          </button>
        </div>
      ),
    },
  ];

  const tabs: EntityDrawerTab[] = selected ? [
    {
      id: 'summary',
      label: 'Summary',
      content: (
        <BankSummaryTab
          key={`${selected.id}-${saveVersion}`}
          bank={selected}
          saving={updateMutation.isPending}
          error={drawerError}
          onSave={(values) => updateMutation.mutate({ id: selected.id, values })}
        />
      ),
    },
    { id: 'metadata', label: 'Metadata', content: <BankMetadataTab bank={selected} /> },
    { id: 'audit', label: 'Audit', content: <BankAuditTab bankId={selected.id} /> },
  ] : [];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <p className="text-sm text-muted max-w-xl">
          Custom banks added here appear alongside the ~40 built-in banks everywhere a user picks a
          bank for an account. Built-in banks aren't editable from here. Click a bank to view its
          details.
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
            <Plus size={15} /> Add bank
          </button>
        )}
      </div>

      {showCreate && (
        <BankCreateForm
          submitting={createMutation.isPending}
          error={formError}
          onCancel={() => {
            setShowCreate(false);
            setFormError(null);
          }}
          onSubmit={(values) => createMutation.mutate(values)}
        />
      )}

      <DataTable
        columns={columns}
        rows={banks?.content ?? []}
        keyFor={(bank) => bank.id}
        loading={isLoading}
        emptyMessage="No custom banks added yet."
      />
      {banks && (
        <Pagination
          page={page}
          totalPages={banks.totalPages}
          totalElements={banks.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
      {deleteMutation.isError && (
        <p className="text-sm text-danger">
          {(deleteMutation.error as any)?.response?.data?.message ?? 'Could not delete this bank — it may still be in use by an account.'}
        </p>
      )}

      <EntityDrawer
        open={selected !== null}
        onClose={() => setSelected(null)}
        title={selected?.shortName ?? ''}
        subtitle={selected?.officialName}
        tabs={tabs}
      />

      {confirmDeleteBank && (
        <ConfirmDialog
          title={`Remove ${confirmDeleteBank.shortName}?`}
          message="This only works if no account uses it."
          confirmLabel="Remove"
          danger
          onConfirm={() => {
            const id = confirmDeleteBank.id;
            setConfirmDeleteBank(null);
            deleteMutation.mutate(id);
          }}
          onCancel={() => setConfirmDeleteBank(null)}
        />
      )}
    </div>
  );
}

export default function Banks() {
  return (
    <AdminLayout title="Banks" subtitle="Custom banks available alongside the built-in registry">
      <RequirePermission permission="BANK_MANAGE">
        <BanksContent />
      </RequirePermission>
    </AdminLayout>
  );
}
