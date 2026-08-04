import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ArrowLeft, ShieldBan, ShieldCheck, ShieldAlert, Wallet, ArrowLeftRight, Phone, Mail, Calendar,
  Pencil, Trash2,
} from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminAuditApi, adminRolesApi, adminUsersApi } from '../api/endpoints';

// Split out of this file, which had grown to 1770 lines -- the largest source file in the repo and,
// until the accompanying UserDetail.test.tsx, one of the few admin pages with no test at all. This
// is a pure move: every component below is the same code it was, in a new file, grouped exactly the
// way the original already grouped it (each row/form helper travels with the section that uses it).
// No behaviour changed, no abstractions introduced. The permission gating in UserDetailContent is
// what the new test suite pins, because dropping one of those guards during a move is the failure
// that would leak another user's financial data.
import { AccountsSection } from './user-detail/AccountsSection';
import { AnalyticsSection } from './user-detail/AnalyticsSection';
import { EditProfileForm } from './user-detail/EditProfileForm';
import { LearningSection } from './user-detail/LearningSection';
import { MerchantsSection } from './user-detail/MerchantsSection';
import { RelationshipsSection } from './user-detail/RelationshipsSection';
import { RulesSection } from './user-detail/RulesSection';
import { TransactionsSection } from './user-detail/TransactionsSection';
import { WorkspaceSection } from './user-detail/WorkspaceSection';

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
      {hasPermission('RELATIONSHIP_MANAGE') && <RelationshipsSection userId={id} />}
      {hasPermission('MERCHANT_MANAGE') && <LearningSection userId={id} />}
      {hasPermission('PLATFORM_ANALYTICS_VIEW') && <AnalyticsSection userId={id} />}
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
