import { useState } from 'react';
import { Link } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ShieldBan, ShieldCheck, Plus } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { FormPanel } from '../components/FormPanel';
import { DataTable, type DataTableColumn } from '../components/DataTable';
import { Pagination } from '../components/Pagination';
import { FilterBar } from '../components/FilterBar';
import { useSavedViews } from '../hooks/useSavedViews';
import { useAdminAuth } from '../context/AdminAuthContext';
import { useNotify } from '../context/NotificationContext';
import { adminUsersApi, adminRolesApi } from '../api/endpoints';
import type { UserSummaryDto, CreateUserRequest, RoleDto } from '../types';

const PAGE_SIZE = 20;

const BLANK_USER: CreateUserRequest = { email: '', password: '', fullName: '', phoneNumber: '' };

/** Support-assisted signup -- an admin creating an account on someone's behalf (AuthService
 *  .adminCreateUser). Same validation rules as self-service registration on the backend; this
 *  form doesn't duplicate them client-side, it just surfaces whatever message the API returns.
 *
 *  Role is deliberately NOT sent as part of the create request itself -- every new account
 *  already gets the USER role by default (AuthService.createUserRecord's own User.role field
 *  default), same as self-service registration. Picking anything else here is a second,
 *  separate call to the existing role-assignment endpoint (RoleAdminController.assignRole) once
 *  the account exists, reusing that capability rather than teaching the create endpoint a new
 *  concept it didn't have before. */
function CreateUserForm({
  onCancel, onSubmit, submitting, error,
}: {
  onCancel: () => void;
  onSubmit: (values: CreateUserRequest, role: string) => void;
  submitting: boolean;
  error: string | null;
}) {
  const [form, setForm] = useState<CreateUserRequest>(BLANK_USER);
  const [role, setRole] = useState('USER');

  const { data: roles } = useQuery({
    queryKey: ['admin-roles-for-create-user'],
    queryFn: () => adminRolesApi.listRoles(),
  });
  // USER always offered even before the fetch resolves (or if it fails) -- it's the real
  // default every new account gets regardless of what this dropdown shows, so the form is never
  // stuck without a valid selection.
  const roleOptions: RoleDto[] = roles?.length ? roles : [{ id: 'USER', name: 'USER', description: 'Standard user', permissions: [] }];

  return (
    <FormPanel
      title="Create a user"
      onCancel={onCancel}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(form, role);
      }}
      error={error}
      submitting={submitting}
      submitLabel="Create user"
    >
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Full name</label>
          <input
            required
            value={form.fullName}
            onChange={(e) => setForm({ ...form, fullName: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Email</label>
          <input
            required
            type="email"
            value={form.email}
            onChange={(e) => setForm({ ...form, email: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Phone number</label>
          <input
            required
            placeholder="+91XXXXXXXXXX"
            value={form.phoneNumber}
            onChange={(e) => setForm({ ...form, phoneNumber: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Temporary password</label>
          <input
            required
            type="text"
            minLength={8}
            value={form.password}
            onChange={(e) => setForm({ ...form, password: e.target.value })}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
        <div>
          <label className="text-xs font-medium text-muted mb-1 block">Role</label>
          <select
            value={role}
            onChange={(e) => setRole(e.target.value)}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          >
            {roleOptions.map((r) => (
              <option key={r.name} value={r.name}>{r.name}</option>
            ))}
          </select>
        </div>
      </div>
    </FormPanel>
  );
}

function StatusBadge({ status }: { status: string }) {
  const isActive = status === 'ACTIVE';
  return (
    <span
      className={`text-xs font-semibold rounded-full px-2.5 py-1 ${
        isActive ? 'bg-success-bg text-success' : 'bg-danger-bg text-danger'
      }`}
    >
      {isActive ? 'Active' : 'Suspended'}
    </span>
  );
}

// Same reasoning as AuditFilterValues in AuditLog.tsx: the explicit index signature is required
// for this to satisfy useSavedViews<T>/FilterBar<T>'s Record<string, string> constraint.
interface UserFilterValues {
  [key: string]: string;
  q: string;
  status: string;
}

function UsersTable() {
  const { hasPermission } = useAdminAuth();
  const canModify = hasPermission('USER_DELETE');
  const canCreate = hasPermission('USER_CREATE');
  const queryClient = useQueryClient();
  const notify = useNotify();

  const [q, setQ] = useState('');
  const [searchInput, setSearchInput] = useState('');
  const [status, setStatus] = useState('');
  const [page, setPage] = useState(0);
  const [showCreate, setShowCreate] = useState(false);
  const [createError, setCreateError] = useState<string | null>(null);
  // Admin Portal Phase 5 -- FilterBar reference implementation #2 (see AuditLog.tsx for #1).
  // Users.tsx already had search+status filtering before this phase; this only moves it onto the
  // shared component and adds a saved-views dropdown, it doesn't change what's filterable.
  const savedViews = useSavedViews<UserFilterValues>('finora-admin-views-users');

  const { data, isLoading } = useQuery({
    queryKey: ['admin-users', q, status, page],
    queryFn: () => adminUsersApi.list({ q: q || undefined, status: status || undefined, page, size: PAGE_SIZE }),
  });

  const suspendMutation = useMutation({
    mutationFn: (id: string) => adminUsersApi.suspend(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-users'] });
      notify.success('User suspended.');
    },
    onError: (err: any) => notify.error(err?.response?.data?.message ?? 'Failed to suspend user.'),
  });
  const reactivateMutation = useMutation({
    mutationFn: (id: string) => adminUsersApi.reactivate(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['admin-users'] });
      notify.success('User reactivated.');
    },
    onError: (err: any) => notify.error(err?.response?.data?.message ?? 'Failed to reactivate user.'),
  });
  const createMutation = useMutation({
    // Role assignment is a second, separate call to the existing role-assignment endpoint --
    // see CreateUserForm's own doc comment for why this isn't taught to the create endpoint
    // itself. Skipped entirely when USER (the real default every new account already gets) is
    // selected, so the common case stays exactly one request, same as before this change.
    //
    // Bug fix: if assignRole() failed, this used to let that exception propagate out of the
    // whole mutationFn, which meant onError fired instead of onSuccess -- the modal stayed open
    // and the user list never refreshed, even though the account HAD actually been created by
    // the first call. An admin closing the modal at that point would have no idea a user now
    // exists (just without the role they picked), and re-submitting the same email would only
    // surface that as a confusing "already exists" conflict. Now the two outcomes are reported
    // separately: creation success always closes the modal and refreshes the list (that's the
    // real, primary outcome), and a role-assignment failure surfaces as its own warning toast
    // rather than masquerading as the whole operation having failed.
    mutationFn: async ({ values, role }: { values: CreateUserRequest; role: string }) => {
      const created = await adminUsersApi.create(values);
      if (role !== 'USER') {
        try {
          await adminRolesApi.assignRole(created.id, role);
        } catch (err: any) {
          notify.error(
            `User created, but assigning the ${role} role failed: ${err?.response?.data?.message ?? 'unknown error'}. You can assign it from Roles & Permissions.`
          );
        }
      }
      return created;
    },
    onSuccess: () => {
      setShowCreate(false);
      setCreateError(null);
      queryClient.invalidateQueries({ queryKey: ['admin-users'] });
    },
    onError: (err: any) => setCreateError(err?.response?.data?.message ?? 'Failed to create user.'),
  });

  function runSearch() {
    setQ(searchInput.trim());
    setPage(0);
  }

  function applyView(values: UserFilterValues) {
    setSearchInput(values.q);
    setQ(values.q);
    setStatus(values.status);
    setPage(0);
  }

  const totalPages = data?.totalPages ?? 0;

  // The Actions column only makes sense (and is only rendered) for an admin who actually holds
  // USER_DELETE -- built conditionally here instead of the old pattern of guarding the <th> and
  // <td> separately, which was two places the same `canModify` check had to stay in sync.
  const columns: DataTableColumn<UserSummaryDto>[] = [
    {
      header: 'Name',
      render: (u) => <Link to={`/users/${u.id}`} className="font-medium text-ink hover:text-primary">{u.fullName}</Link>,
    },
    { header: 'Email', render: (u) => u.email, cellClassName: 'text-muted' },
    { header: 'Roles', render: (u) => u.roleNames.join(', '), cellClassName: 'text-muted' },
    { header: 'Status', render: (u) => <StatusBadge status={u.status} /> },
    { header: 'Joined', render: (u) => new Date(u.createdAt).toLocaleDateString(), cellClassName: 'text-muted' },
  ];
  if (canModify) {
    columns.push({
      header: 'Actions',
      headerClassName: 'text-right',
      cellClassName: 'text-right',
      render: (u) => (
        u.status === 'ACTIVE' ? (
          <button
            type="button"
            disabled={suspendMutation.isPending}
            onClick={() => suspendMutation.mutate(u.id)}
            className="inline-flex items-center gap-1.5 text-xs font-medium text-danger hover:bg-danger-bg rounded-lg px-2.5 py-1.5"
          >
            <ShieldBan size={14} /> Suspend
          </button>
        ) : (
          <button
            type="button"
            disabled={reactivateMutation.isPending}
            onClick={() => reactivateMutation.mutate(u.id)}
            className="inline-flex items-center gap-1.5 text-xs font-medium text-success hover:bg-success-bg rounded-lg px-2.5 py-1.5"
          >
            <ShieldCheck size={14} /> Reactivate
          </button>
        )
      ),
    });
  }

  return (
    <div>
      <FilterBar<UserFilterValues>
        fields={[
          { type: 'search', key: 'q', value: searchInput, onChange: setSearchInput, placeholder: 'Search name, email, phone…' },
          {
            type: 'select', key: 'status', value: status,
            onChange: (v) => { setStatus(v); setPage(0); },
            placeholder: 'All statuses',
            options: [{ label: 'Active', value: 'ACTIVE' }, { label: 'Suspended', value: 'SUSPENDED' }],
          },
        ]}
        onApply={runSearch}
        savedViews={{
          views: savedViews.views,
          currentValues: { q: searchInput.trim(), status },
          onApply: applyView,
          onSave: savedViews.save,
          onDelete: savedViews.remove,
        }}
        trailingActions={canCreate && !showCreate ? (
          <button
            type="button"
            onClick={() => {
              setShowCreate(true);
              setCreateError(null);
            }}
            className="inline-flex items-center gap-1.5 bg-ink hover:bg-ink/90 text-white text-sm font-semibold rounded-lg px-4 py-2.5 flex-shrink-0"
          >
            <Plus size={15} /> New user
          </button>
        ) : undefined}
      />

      {showCreate && (
        <div className="mb-5">
          <CreateUserForm
            submitting={createMutation.isPending}
            error={createError}
            onCancel={() => {
              setShowCreate(false);
              setCreateError(null);
            }}
            onSubmit={(values, role) => createMutation.mutate({ values, role })}
          />
        </div>
      )}

      <DataTable
        columns={columns}
        rows={data?.content}
        keyFor={(u) => u.id}
        loading={isLoading}
        emptyMessage="No users match this search."
      />

      {data && (
        <Pagination
          page={page}
          totalPages={totalPages}
          totalElements={data.totalElements}
          pageSize={PAGE_SIZE}
          onPageChange={setPage}
        />
      )}
    </div>
  );
}

export default function Users() {
  return (
    <AdminLayout title="Users" subtitle="Search, review, and manage every account on the platform">
      <RequirePermission permission="USER_VIEW">
        <UsersTable />
      </RequirePermission>
    </AdminLayout>
  );
}
