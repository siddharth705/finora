import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft, ShieldBan, ShieldCheck, ShieldAlert, Wallet, ArrowLeftRight, Phone, Mail, Calendar,
  Pencil, Plus, Trash2, X, Store, ListFilter, Sparkles, GitMerge,
} from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { useAdminAuth } from '../context/AdminAuthContext';
import { useNotify } from '../context/NotificationContext';
import {
  adminAccountsApi, adminAuditApi, adminRolesApi, adminTransactionsApi, adminUserLearningApi,
  adminUserMerchantsApi, adminUserRulesApi, adminUsersApi, adminUserWorkspaceApi, banksApi,
} from '../api/endpoints';
import type {
  AccountDto, AdminUpdateUserRequest, CreateAccountRequest, CreateRuleRequest, LearningSummaryDto,
  LearningTimelineEntry, MerchantDto, RuleDto, UpdateRuleRequest, WorkspaceSummaryDto,
} from '../types';

const ACCOUNT_TYPES = ['SAVINGS', 'CREDIT_CARD', 'WALLET', 'INVESTMENT'];

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

/** Edits fullName/phoneNumber/lowBalanceThreshold/timezone -- deliberately not email or password,
 *  same scope AdminUpdateUserRequest allows on the backend (see AdminDtos.java's comment there). */
function EditProfileForm({
  userId, initial, onDone,
}: {
  userId: string;
  initial: { fullName: string; phoneNumber: string | null };
  onDone: () => void;
}) {
  const queryClient = useQueryClient();
  const [fullName, setFullName] = useState(initial.fullName);
  const [phoneNumber, setPhoneNumber] = useState(initial.phoneNumber ?? '');
  const [error, setError] = useState<string | null>(null);

  const updateMutation = useMutation({
    mutationFn: (req: AdminUpdateUserRequest) => adminUsersApi.update(userId, req),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin-user', userId] });
      void queryClient.invalidateQueries({ queryKey: ['admin-users'] });
      onDone();
    },
    onError: (err: any) => setError(errorMessage(err, 'Failed to update profile.')),
  });

  return (
    <form
      onSubmit={(e) => {
        e.preventDefault();
        updateMutation.mutate({ fullName: fullName.trim(), phoneNumber: phoneNumber.trim() });
      }}
      className="bg-bg border border-border rounded-xl2 p-4 space-y-3 mt-4"
    >
      {error && <p className="text-sm text-danger bg-danger-bg rounded-lg px-3 py-2">{error}</p>}
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Full name</label>
          <input
            required
            value={fullName}
            onChange={(e) => setFullName(e.target.value)}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Phone number</label>
          <input
            required
            value={phoneNumber}
            onChange={(e) => setPhoneNumber(e.target.value)}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
      </div>
      <div className="flex justify-end gap-2">
        <button type="button" onClick={onDone} className="text-sm font-medium text-muted px-3.5 py-2 rounded-lg hover:bg-card">
          Cancel
        </button>
        <button
          type="submit"
          disabled={updateMutation.isPending}
          className="bg-primary hover:bg-primary-dark text-white text-sm font-semibold rounded-lg px-4 py-2 disabled:opacity-50"
        >
          Save
        </button>
      </div>
    </form>
  );
}

const BLANK_ACCOUNT: CreateAccountRequest = {
  name: '', accountType: 'SAVINGS', balance: 0, bankId: '', accountHolderName: '', accountNumberMasked: '',
};

function AccountForm({
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
          <label className="text-xs font-medium text-muted mb-1 block">Name</label>
          <input
            required
            value={form.name}
            onChange={(e) => setForm({ ...form, name: e.target.value })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Type</label>
          <select
            value={form.accountType}
            onChange={(e) => setForm({ ...form, accountType: e.target.value })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          >
            {ACCOUNT_TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
          </select>
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Bank</label>
          <select
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
          <label className="text-xs font-medium text-muted mb-1 block">
            {form.accountType === 'CREDIT_CARD' ? 'Outstanding balance' : 'Balance'}
          </label>
          <input
            required
            type="number"
            step="0.01"
            value={form.balance}
            onChange={(e) => setForm({ ...form, balance: Number(e.target.value) })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Account holder name</label>
          <input
            value={form.accountHolderName ?? ''}
            onChange={(e) => setForm({ ...form, accountHolderName: e.target.value })}
            className="w-full bg-card border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Masked account number</label>
          <input
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
          className="bg-primary hover:bg-primary-dark text-white text-sm font-semibold rounded-lg px-4 py-2 disabled:opacity-50"
        >
          {editing ? 'Save changes' : 'Add account'}
        </button>
      </div>
    </form>
  );
}

function AccountsSection({ userId }: { userId: string }) {
  const { hasPermission } = useAdminAuth();
  const queryClient = useQueryClient();
  const [showCreate, setShowCreate] = useState(false);
  const [editingAccount, setEditingAccount] = useState<AccountDto | null>(null);
  const [error, setError] = useState<string | null>(null);

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
                    onClick={() => {
                      if (confirm(`Delete account "${account.name}"? This also removes its transactions.`)) {
                        deleteMutation.mutate(account.id);
                      }
                    }}
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
    </div>
  );
}

function TransactionsSection({ userId }: { userId: string }) {
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

function MerchantRow({
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

  // Every OTHER merchant this same user has -- merging absorbs one of them into this row (see
  // MerchantService.merge()'s doc comment on the backend for exactly what that repoints).
  const otherMerchants = allMerchants.filter((m) => m.id !== merchant.id);

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

/** Per-user merchant management, admin-proxy version of the self-service Merchant Management
 *  console (see AdminUserMerchantController's class comment on why confirm-category/undo/
 *  reset-learning aren't mirrored here -- only rename and merge, the two actions that make sense
 *  on a name/duplicate-cleanup basis rather than in the context of one specific transaction). */
function MerchantsSection({ userId }: { userId: string }) {
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
function InlineRuleForm({
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
        <select
          value={form.field}
          onChange={(e) => setForm({ ...form, field: e.target.value })}
          className="bg-card border border-border rounded-lg px-2.5 py-1.5 text-xs"
        >
          {RULE_FIELDS.map((f) => <option key={f} value={f}>{f}</option>)}
        </select>
        <select
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
          className="text-xs font-semibold text-white bg-primary hover:bg-primary-dark rounded-lg px-3 py-1.5 disabled:opacity-50"
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

function RuleRow({ userId, rule }: { userId: string; rule: RuleDto }) {
  const queryClient = useQueryClient();
  const [editing, setEditing] = useState(false);
  const [error, setError] = useState<string | null>(null);

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
          onClick={() => {
            if (confirm('Delete this rule?')) deleteMutation.mutate();
          }}
          className="w-7 h-7 rounded-lg hover:bg-danger-bg text-muted hover:text-danger inline-flex items-center justify-center"
        >
          <Trash2 size={13} />
        </button>
      </div>
    </div>
  );
}

/** Per-user rule management, admin-proxy version of the self-service rule authoring the User
 *  Portal used to expose directly (see the architecture doc on why Rules moved off the main nav
 *  -- this restores the same create/edit/delete capability for support staff acting on a
 *  specific account, reusing RuleService's exact USER-scope logic via AdminUserRuleController). */
function RulesSection({ userId }: { userId: string }) {
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

/** Read-only Learning Engine visibility for a specific user -- AdminUserLearningController
 *  proxies the exact same MerchantLearningService.timeline()/summary() the self-service Learning
 *  Engine page used before it moved off the User Portal's main nav. No actions here on purpose:
 *  confirm/undo/reset live on MerchantController (self-service only), see
 *  AdminUserLearningController's class comment for why they aren't mirrored to the admin console. */
function LearningSection({ userId }: { userId: string }) {
  const { data: summary, isLoading: summaryLoading } = useQuery<LearningSummaryDto>({
    queryKey: ['admin-user-learning-summary', userId],
    queryFn: () => adminUserLearningApi.summary(userId),
  });
  const { data: timeline, isLoading: timelineLoading } = useQuery({
    queryKey: ['admin-user-learning-timeline', userId],
    queryFn: () => adminUserLearningApi.timeline(userId),
  });

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center gap-2 mb-3">
        <Sparkles size={15} className="text-primary" />
        <h3 className="text-sm font-semibold text-ink">Learning Engine</h3>
      </div>

      {!summaryLoading && summary && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-3 mb-4">
          <div className="bg-bg border border-border rounded-lg p-3">
            <p className="text-lg font-bold text-ink">{summary.learnedMerchants}</p>
            <p className="text-xs text-muted">Learned merchants</p>
          </div>
          <div className="bg-bg border border-border rounded-lg p-3">
            <p className="text-lg font-bold text-ink">{summary.totalConfirmations}</p>
            <p className="text-xs text-muted">Confirmations</p>
          </div>
          <div className="bg-bg border border-border rounded-lg p-3">
            <p className="text-lg font-bold text-warning">{summary.correctedCount}</p>
            <p className="text-xs text-muted">Corrections</p>
          </div>
          <div className="bg-bg border border-border rounded-lg p-3">
            <p className="text-lg font-bold text-ink">{summary.resetCount}</p>
            <p className="text-xs text-muted">Resets</p>
          </div>
        </div>
      )}

      {timelineLoading && <p className="text-sm text-muted">Loading…</p>}
      {!timelineLoading && (timeline ?? []).length === 0 && (
        <p className="text-sm text-muted">No learning activity recorded for this user yet.</p>
      )}
      <div>
        {timeline?.slice(0, 10).map((entry: LearningTimelineEntry) => (
          <div key={entry.id} className="flex items-center justify-between text-sm py-2 border-b border-border last:border-b-0 gap-3">
            <div className="min-w-0 flex-1">
              <p className="text-ink truncate">
                <span className="font-medium">{entry.merchantName}</span> -- {entry.action.toLowerCase()}
                {entry.newCategoryName ? ` -> ${entry.newCategoryName}` : ''}
              </p>
              <p className="text-xs text-muted">{new Date(entry.createdAt).toLocaleString()}</p>
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

const WORKSPACE_HEALTH_SIGNALS: { key: keyof WorkspaceSummaryDto['health']; label: string }[] = [
  { key: 'rulesEnabled', label: 'Rules Enabled' },
  { key: 'merchantLearningActive', label: 'Merchant Learning Active' },
  { key: 'reconciliationHealthy', label: 'Reconciliation Healthy' },
  { key: 'recurringDetectionHealthy', label: 'Recurring Detection Healthy' },
  { key: 'auditLoggingHealthy', label: 'Audit Logging Healthy' },
];

const WORKSPACE_CONFIDENCE_TIER_COLOR: Record<string, string> = {
  HIGH: '#16a34a', MEDIUM: '#f59e0b', LOW: '#ef4444', UNCONFIRMED: '#94a3b8',
};

function fmtWorkspaceActivityAction(action: string) {
  return action.toLowerCase().replace(/_/g, ' ').replace(/^./, (c) => c.toUpperCase());
}

/** Consolidated Intelligence Workspace card for a specific user -- the exact same panel the
 *  self-service User Portal Dashboard used to show (health badges + Snapshot / Merchant
 *  Confidence / Recent Activity), before it moved off that app's main nav. AdminUserWorkspaceController
 *  proxies the exact same WorkspaceDashboardService.summarize() the self-service version called, and
 *  already returns every field this card needs in one response -- nothing here is a second fetch of
 *  data another section on this page already has. Deliberately its own card rather than folded into
 *  MerchantsSection/RulesSection/LearningSection above: those are action surfaces (create a rule,
 *  rename a merchant, review a learning event); this is purely "is Finora's own engine working for
 *  this account", the same distinction the original User Portal draws between its KPI cards and this
 *  section (see the removed frontend/src/pages/Dashboard.tsx's WorkspaceSection doc comment). */
function WorkspaceSection({ userId }: { userId: string }) {
  const { data: workspace, isLoading } = useQuery<WorkspaceSummaryDto>({
    queryKey: ['admin-user-workspace', userId],
    queryFn: () => adminUserWorkspaceApi.get(userId),
  });

  const confidenceTotal = workspace
    ? Object.values(workspace.confidenceDistribution).reduce((s, v) => s + v, 0)
    : 0;

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
      <div className="flex items-center gap-2 mb-3">
        <GitMerge size={15} className="text-primary" />
        <h3 className="text-sm font-semibold text-ink">Intelligence Workspace</h3>
      </div>

      {isLoading && <p className="text-sm text-muted">Loading…</p>}

      {!isLoading && workspace && (
        <>
          <div className="flex flex-wrap gap-2 mb-4">
            {WORKSPACE_HEALTH_SIGNALS.map(({ key, label }) => {
              const healthy = workspace.health[key];
              const Icon = healthy ? ShieldCheck : ShieldAlert;
              return (
                <span
                  key={key}
                  className={`inline-flex items-center gap-1.5 text-xs font-semibold rounded-full px-2.5 py-1 ${
                    healthy ? 'bg-success-bg text-success' : 'bg-danger-bg text-danger'
                  }`}
                >
                  <Icon size={13} /> {label}
                </span>
              );
            })}
          </div>

          <div className="grid md:grid-cols-3 gap-x-8 gap-y-4">
            <div>
              <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Snapshot</p>
              <dl className="space-y-1.5 text-sm">
                <div className="flex justify-between"><dt className="text-muted">Merchants learned</dt><dd className="text-ink font-medium">{workspace.learnedMerchants} / {workspace.totalMerchants}</dd></div>
                <div className="flex justify-between"><dt className="text-muted">Active rules</dt><dd className="text-ink font-medium">{workspace.activeRules}</dd></div>
                <div className="flex justify-between"><dt className="text-muted">Relationships</dt><dd className="text-ink font-medium">{workspace.relationships}</dd></div>
                <div className="flex justify-between">
                  <dt className="text-muted">Auto-categorized</dt>
                  <dd className="text-ink font-medium">
                    {workspace.categorizationAccuracy !== null ? `${workspace.categorizationAccuracy.toFixed(0)}%` : '—'}
                  </dd>
                </div>
                <div className="flex justify-between"><dt className="text-muted">Duplicates / Transfers / Refunds</dt><dd className="text-ink font-medium">{workspace.duplicateMatches} / {workspace.transferMatches} / {workspace.refundMatches}</dd></div>
                <div className="flex justify-between"><dt className="text-muted">Recurring transactions</dt><dd className="text-ink font-medium">{workspace.recurringTransactions}</dd></div>
              </dl>
            </div>

            <div>
              <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Merchant confidence</p>
              {confidenceTotal === 0 ? (
                <p className="text-xs text-muted">No merchants learned yet.</p>
              ) : (
                <div className="space-y-1.5">
                  <div className="h-2 rounded-full overflow-hidden flex bg-black/5">
                    {Object.entries(workspace.confidenceDistribution).map(([tier, count]) => (
                      count > 0 && (
                        <div key={tier} style={{ width: `${(count / confidenceTotal) * 100}%`, background: WORKSPACE_CONFIDENCE_TIER_COLOR[tier] ?? '#94a3b8' }} />
                      )
                    ))}
                  </div>
                  <div className="flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-muted">
                    {Object.entries(workspace.confidenceDistribution).map(([tier, count]) => (
                      <span key={tier} className="flex items-center gap-1">
                        <span className="w-1.5 h-1.5 rounded-full" style={{ background: WORKSPACE_CONFIDENCE_TIER_COLOR[tier] ?? '#94a3b8' }} />
                        {tier} ({count})
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </div>

            <div>
              <p className="text-[11px] uppercase tracking-wide text-muted mb-2">Recent activity</p>
              {workspace.recentActivity.length === 0 ? (
                <p className="text-xs text-muted">No activity recorded yet.</p>
              ) : (
                <ul className="space-y-1.5">
                  {workspace.recentActivity.slice(0, 5).map((a) => (
                    <li key={a.id} className="text-xs text-ink truncate">{fmtWorkspaceActivityAction(a.action)}</li>
                  ))}
                </ul>
              )}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function UserDetailContent({ id }: { id: string }) {
  const { hasPermission } = useAdminAuth();
  const canModify = hasPermission('USER_DELETE');
  const canEditProfile = hasPermission('USER_UPDATE');
  const canManageRoles = hasPermission('ROLE_MANAGE');
  const canViewAccounts = hasPermission('USER_VIEW');
  const queryClient = useQueryClient();
  const [selectedRole, setSelectedRole] = useState('');
  const [editingProfile, setEditingProfile] = useState(false);

  const { data: user, isLoading } = useQuery({
    queryKey: ['admin-user', id],
    queryFn: () => adminUsersApi.get(id),
  });
  const { data: auditLogs } = useQuery({
    queryKey: ['admin-user-audit', id],
    queryFn: () => adminAuditApi.forUser(id),
    enabled: hasPermission('AUDIT_VIEW'),
  });
  const { data: roles } = useQuery({
    queryKey: ['admin-roles'],
    queryFn: () => adminRolesApi.listRoles(),
    enabled: canManageRoles,
  });

  function invalidateUser() {
    void queryClient.invalidateQueries({ queryKey: ['admin-user', id] });
    void queryClient.invalidateQueries({ queryKey: ['admin-users'] });
  }

  const suspendMutation = useMutation({ mutationFn: () => adminUsersApi.suspend(id), onSuccess: invalidateUser });
  const reactivateMutation = useMutation({ mutationFn: () => adminUsersApi.reactivate(id), onSuccess: invalidateUser });
  const assignRoleMutation = useMutation({
    mutationFn: (roleName: string) => adminRolesApi.assignRole(id, roleName),
    onSuccess: () => {
      setSelectedRole('');
      invalidateUser();
    },
  });
  const revokeRoleMutation = useMutation({
    mutationFn: (roleName: string) => adminRolesApi.revokeRole(id, roleName),
    onSuccess: invalidateUser,
  });

  if (isLoading || !user) {
    return <p className="text-muted text-sm">Loading…</p>;
  }

  const assignableRoles = (roles ?? []).filter((r) => !user.roleNames.includes(r.name));

  return (
    <div className="space-y-6">
      <Link to="/users" className="inline-flex items-center gap-1.5 text-sm text-muted hover:text-ink">
        <ArrowLeft size={15} /> Back to Users
      </Link>

      <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
        <div className="flex items-start justify-between mb-5">
          <div>
            <h2 className="text-lg font-bold text-ink">{user.fullName}</h2>
            <span
              className={`inline-block mt-1.5 text-xs font-semibold rounded-full px-2.5 py-1 ${
                user.status === 'ACTIVE' ? 'bg-success-bg text-success' : 'bg-danger-bg text-danger'
              }`}
            >
              {user.status === 'ACTIVE' ? 'Active' : 'Suspended'}
            </span>
          </div>
          <div className="flex items-center gap-2">
            {canEditProfile && !editingProfile && (
              <button
                type="button"
                onClick={() => setEditingProfile(true)}
                className="inline-flex items-center gap-1.5 text-sm font-medium text-ink border border-border hover:bg-bg rounded-lg px-3.5 py-2"
              >
                <Pencil size={14} /> Edit profile
              </button>
            )}
            {canModify && (
              user.status === 'ACTIVE' ? (
                <button
                  type="button"
                  disabled={suspendMutation.isPending}
                  onClick={() => suspendMutation.mutate()}
                  className="inline-flex items-center gap-1.5 text-sm font-medium text-danger border border-danger/30 hover:bg-danger-bg rounded-lg px-3.5 py-2"
                >
                  <ShieldBan size={15} /> Suspend account
                </button>
              ) : (
                <button
                  type="button"
                  disabled={reactivateMutation.isPending}
                  onClick={() => reactivateMutation.mutate()}
                  className="inline-flex items-center gap-1.5 text-sm font-medium text-success border border-success/30 hover:bg-success-bg rounded-lg px-3.5 py-2"
                >
                  <ShieldCheck size={15} /> Reactivate account
                </button>
              )
            )}
          </div>
        </div>

        {editingProfile ? (
          <EditProfileForm
            userId={id}
            initial={{ fullName: user.fullName, phoneNumber: user.phoneNumber }}
            onDone={() => setEditingProfile(false)}
          />
        ) : (
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4 text-sm">
            <div className="flex items-center gap-2 text-muted"><Mail size={14} /> {user.email}</div>
            <div className="flex items-center gap-2 text-muted"><Phone size={14} /> {user.phoneNumber ?? '—'} {user.phoneVerified ? '(verified)' : '(unverified)'}</div>
            <div className="flex items-center gap-2 text-muted"><Calendar size={14} /> Joined {new Date(user.createdAt).toLocaleDateString()}</div>
            <div className="flex items-center gap-2 text-muted">Roles: {user.roleNames.join(', ')}</div>
          </div>
        )}

        <div className="grid grid-cols-2 gap-4 mt-5 pt-5 border-t border-border">
          <div className="flex items-center gap-2.5">
            <Wallet size={18} className="text-primary" />
            <div>
              <p className="text-lg font-bold text-ink">{user.accountCount}</p>
              <p className="text-xs text-muted">Accounts</p>
            </div>
          </div>
          <div className="flex items-center gap-2.5">
            <ArrowLeftRight size={18} className="text-primary" />
            <div>
              <p className="text-lg font-bold text-ink">{user.transactionCount}</p>
              <p className="text-xs text-muted">Transactions</p>
            </div>
          </div>
        </div>
      </div>

      {canViewAccounts && <AccountsSection userId={id} />}
      {hasPermission('TRANSACTION_DELETE') && <TransactionsSection userId={id} />}
      {hasPermission('MERCHANT_MANAGE') && <MerchantsSection userId={id} />}
      {hasPermission('RULE_MANAGE') && <RulesSection userId={id} />}
      {hasPermission('MERCHANT_MANAGE') && <LearningSection userId={id} />}
      {hasPermission('RECONCILIATION_VIEW') && <WorkspaceSection userId={id} />}

      {canManageRoles && (
        <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
          <h3 className="text-sm font-semibold text-ink mb-3">Roles</h3>
          <div className="flex flex-wrap gap-2 mb-4">
            {user.roleNames.map((name) => (
              <span key={name} className="inline-flex items-center gap-2 bg-bg border border-border rounded-full pl-3 pr-1.5 py-1 text-xs text-ink">
                {name}
                <button
                  type="button"
                  title="Revoke this role"
                  onClick={() => revokeRoleMutation.mutate(name)}
                  className="w-4 h-4 rounded-full bg-border hover:bg-danger hover:text-white text-[10px] flex items-center justify-center"
                >
                  ×
                </button>
              </span>
            ))}
          </div>
          {assignableRoles.length > 0 && (
            <div className="flex items-center gap-2">
              <select
                value={selectedRole}
                onChange={(e) => setSelectedRole(e.target.value)}
                className="bg-bg border border-border rounded-lg px-3 py-2 text-sm"
              >
                <option value="">Grant a role…</option>
                {assignableRoles.map((r) => (
                  <option key={r.id} value={r.name}>{r.name}</option>
                ))}
              </select>
              <button
                type="button"
                disabled={!selectedRole || assignRoleMutation.isPending}
                onClick={() => assignRoleMutation.mutate(selectedRole)}
                className="bg-primary hover:bg-primary-dark text-white text-sm font-semibold rounded-lg px-3.5 py-2 disabled:opacity-50"
              >
                Grant
              </button>
            </div>
          )}
        </div>
      )}

      {hasPermission('AUDIT_VIEW') && (
        <div className="bg-card border border-border rounded-xl2 shadow-card p-6">
          <h3 className="text-sm font-semibold text-ink mb-3">Recent activity</h3>
          <div className="space-y-2">
            {(auditLogs ?? []).length === 0 && <p className="text-sm text-muted">No recorded activity for this account.</p>}
            {(auditLogs ?? []).slice(0, 20).map((log) => (
              <div key={log.id} className="flex items-center justify-between text-sm py-2 border-b border-border last:border-b-0">
                <span className="text-ink font-medium">{log.action}</span>
                <span className="text-muted text-xs">{new Date(log.createdAt).toLocaleString()}</span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

export default function UserDetail() {
  const { id } = useParams<{ id: string }>();
  if (!id) return null;
  return (
    <AdminLayout title="User Detail" subtitle="Profile, accounts, and every admin-managed capability for this account">
      <RequirePermission permission="USER_VIEW">
        <UserDetailContent id={id} />
      </RequirePermission>
    </AdminLayout>
  );
}
