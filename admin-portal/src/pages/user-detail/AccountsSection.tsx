import { useId, useState } from 'react';
import { useAdminAuth } from '../../context/AdminAuthContext';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Pencil, Plus, Trash2, X } from 'lucide-react';
import { adminAccountsApi, banksApi } from '../../api/endpoints';
import type { AccountDto, CreateAccountRequest } from '../../types';
import { errorMessage } from './errorMessage';
import { ConfirmDialog } from '../../components/ConfirmDialog';

const BLANK_ACCOUNT: CreateAccountRequest = {
  name: '', accountType: 'SAVINGS', balance: 0, bankId: '', accountHolderName: '', accountNumberMasked: '',
};

const ACCOUNT_TYPES = ['SAVINGS', 'CREDIT_CARD', 'WALLET', 'INVESTMENT'];

export function AccountForm({
  initial, editing, onCancel, onSubmit, submitting, error,
}: {
  initial: CreateAccountRequest;
  editing: boolean;
  onCancel: () => void;
  onSubmit: (values: CreateAccountRequest) => void;
  submitting: boolean;
  error: string | null;
}) {
  const [form, setForm] = useState<CreateAccountRequest>(initial);
  const { data: banks } = useQuery({ queryKey: ['banks-picker'], queryFn: () => banksApi.search() });
  // Bug fix: every label below was an unassociated sibling of its input/select, not linked via
  // htmlFor/id -- a real axe "label" violation, same class fixed on Login.tsx. useId() keeps ids
  // unique per instance since this form can legitimately mount twice at once (the "Add account"
  // create form and a specific row's edit form are independent state, not mutually exclusive).
  const id = useId();

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(form);
      }}
      className="bg-bg border border-border rounded-xl2 p-4 space-y-3"
    >
      <div className="flex items-center justify-between">
        <h4 className="text-sm font-semibold text-ink">{editing ? 'Edit account' : 'New account'}</h4>
        <button type="button" onClick={onCancel} className="text-muted hover:text-ink"><X size={14} /></button>
      </div>
      {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2">{error}</p>}
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <label htmlFor={`${id}-name`} className="text-xs font-medium text-muted mb-1 block">Name</label>
          <input
            id={`${id}-name`}
            required
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-accountType`} className="text-xs font-medium text-muted mb-1 block">Type</label>
          <select
            id={`${id}-accountType`}
            value={form.accountType}
            onChange={(e) => setForm({ ...form, accountType: e.target.value })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          >
            {ACCOUNT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor={`${id}-bankId`} className="text-xs font-medium text-muted mb-1 block">Bank</label>
          <select
            id={`${id}-bankId`}
            required
            value={form.bankId}
            onChange={(e) => setForm({ ...form, bankId: e.target.value })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          >
            <option value="">Select a bank…</option>
            {banks?.map((b) => <option key={b.id} value={b.id}>{b.shortName}</option>)}
          </select>
        </div>
        <div>
          <label htmlFor={`${id}-balance`} className="text-xs font-medium text-muted mb-1 block">
            {form.accountType === 'CREDIT_CARD' ? 'Outstanding balance' : 'Balance'}
          </label>
          <input
            id={`${id}-balance`}
            required
            type="number"
            step="0.01"
            value={form.balance}
            onChange={(e) => setForm({ ...form, balance: Number(e.target.value) })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-accountHolderName`} className="text-xs font-medium text-muted mb-1 block">Account holder name</label>
          <input
            id={`${id}-accountHolderName`}
            value={form.accountHolderName ?? ''}
            onChange={(e) => setForm({ ...form, accountHolderName: e.target.value })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label htmlFor={`${id}-accountNumberMasked`} className="text-xs font-medium text-muted mb-1 block">Masked account number</label>
          <input
            id={`${id}-accountNumberMasked`}
            placeholder="XXXX1234"
            value={form.accountNumberMasked ?? ''}
            onChange={(e) => setForm({ ...form, accountNumberMasked: e.target.value })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
      </div>
      <div className="flex justify-end gap-2">
        <button type="button" onClick={onCancel} className="text-sm font-medium text-muted px-3.5 py-2 rounded-lg hover:bg-card">
          Cancel
        </button>
        <button
          type="submit"
          disabled={submitting}
          className="bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2 disabled:opacity-50"
        >
          {editing ? 'Save changes' : 'Add account'}
        </button>
      </div>
    </form>
  );
}

export function AccountsSection({ userId }: { userId: string }) {
  const { hasPermission } = useAdminAuth();
  const queryClient = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [editingAccount, setEditingAccount] = useState<AccountDto | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [confirmDeleteAccount, setConfirmDeleteAccount] = useState<AccountDto | null>(null);

  const { data: accounts, isLoading } = useQuery({
    queryKey: ['admin-user-accounts', userId],
    queryFn: () => adminAccountsApi.list(userId),
  });

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['admin-user-accounts', userId] });
    void queryClient.invalidateQueries({ queryKey: ['admin-user', userId] });
  }

  const createMutation = useMutation({
    mutationFn: (values: CreateAccountRequest) => adminAccountsApi.create(userId, values),
    onSuccess: () => {
      setShowCreate(false);
      setError(null);
      invalidate();
    },
    onError: (err: any) => setError(errorMessage(err, 'Failed to create account.')),
  });
  const updateMutation = useMutation({
    mutationFn: ({ id, values }: { id: string; values: CreateAccountRequest }) => adminAccountsApi.update(userId, id, values),
    onSuccess: () => {
      setEditingAccount(null);
      setError(null);
      invalidate();
    },
    onError: (err: any) => setError(errorMessage(err, 'Failed to update account.')),
  });
  const deleteMutation = useMutation({
    mutationFn: (id: string) => adminAccountsApi.delete(userId, id),
    onSuccess: invalidate,
    onError: (err: any) => setError(errorMessage(err, 'Failed to delete account.')),
  });

  const canCreate = hasPermission('ACCOUNT_CREATE');
  const canUpdate = hasPermission('ACCOUNT_UPDATE');
  const canDelete = hasPermission('ACCOUNT_DELETE');

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center justify-between mb-3">
        <h3 className="text-sm font-semibold text-ink">Accounts</h3>
        {canCreate && !showCreate && (
          <button
            type="button"
            onClick={() => {
              setShowCreate(true);
              setError(null);
            }}
            className="inline-flex items-center gap-1.5 text-xs font-semibold text-primary hover:bg-bg rounded-lg px-2.5 py-1.5"
          >
            <Plus size={13} /> Add account
          </button>
        )}
      </div>

      {error && !showCreate && !editingAccount && (
        <p className="text-xs text-danger bg-danger-bg rounded-lg px-2.5 py-1.5 mb-3">{error}</p>
      )}

      {showCreate && (
        <div className="mb-4">
          <AccountForm
            initial={BLANK_ACCOUNT}
            editing={false}
            submitting={createMutation.isPending}
            error={error}
            onCancel={() => {
              setShowCreate(false);
              setError(null);
            }}
            onSubmit={(values) => createMutation.mutate(values)}
          />
        </div>
      )}

      {isLoading && <p className="text-sm text-muted">Loading…</p>}
      {!isLoading && (accounts ?? []).length === 0 && !showCreate && (
        <p className="text-sm text-muted">No accounts on file for this user.</p>
      )}

      <div className="space-y-2">
        {accounts?.map((account) =>
          editingAccount?.id === account.id ? (
            <AccountForm
              key={account.id}
              initial={{
                name: account.name,
                accountType: account.accountType,
                balance: account.balance,
                bankId: account.bank.id,
                accountHolderName: account.accountHolderName ?? '',
                accountNumberMasked: account.accountNumberMasked ?? '',
              }}
              editing
              submitting={updateMutation.isPending}
              error={error}
              onCancel={() => {
                setEditingAccount(null);
                setError(null);
              }}
              onSubmit={(values) => updateMutation.mutate({ id: account.id, values })}
            />
          ) : (
            <div key={account.id} className="flex items-center justify-between border border-border rounded-lg px-4 py-3">
              <div className="flex items-center gap-3">
                <span
                  className="w-8 h-8 rounded-lg flex items-center justify-center text-[10px] font-bold text-white flex-shrink-0"
                  style={{ backgroundColor: account.bank.colorHex || '#64748B' }}
                >
                  {account.bank.initials}
                </span>
                <div>
                  <p className="text-sm font-medium text-ink">{account.name}</p>
                  <p className="text-xs text-muted">{account.bank.shortName} · {account.accountType}</p>
                </div>
              </div>
              <div className="flex items-center gap-3">
                <span className="text-sm font-semibold text-ink">₹{account.balance.toLocaleString('en-IN')}</span>
                {canUpdate && (
                  <button
                    type="button"
                    title="Edit"
                    onClick={() => {
                      setEditingAccount(account);
                      setError(null);
                    }}
                    className="w-7 h-7 rounded-lg hover:bg-bg text-muted hover:text-ink inline-flex items-center justify-center"
                  >
                    <Pencil size={13} />
                  </button>
                )}
                {canDelete && (
                  <button
                    type="button"
                    title="Delete"
                    disabled={deleteMutation.isPending}
                    onClick={() => setConfirmDeleteAccount(account)}
                    className="w-7 h-7 rounded-lg hover:bg-danger-bg text-muted hover:text-danger inline-flex items-center justify-center"
                  >
                    <Trash2 size={13} />
                  </button>
                )}
              </div>
            </div>
          )
        )}
      </div>

      {confirmDeleteAccount && (
        <ConfirmDialog
          title={`Delete account "${confirmDeleteAccount.name}"?`}
          message="This also removes its transactions."
          confirmLabel="Delete"
          danger
          onConfirm={() => {
            const id = confirmDeleteAccount.id;
            setConfirmDeleteAccount(null);
            deleteMutation.mutate(id);
          }}
          onCancel={() => setConfirmDeleteAccount(null)}
        />
      )}
    </div>
  );
}
