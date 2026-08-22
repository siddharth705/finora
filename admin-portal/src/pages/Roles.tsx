import { useId, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { ShieldCheck, Plus, Pencil, Trash2 } from 'lucide-react';
import { AdminLayout } from '../components/AdminLayout';
import { RequirePermission } from '../components/ProtectedRoute';
import { FormPanel } from '../components/FormPanel';
import { ConfirmDialog } from '../components/ConfirmDialog';
import { useAdminAuth } from '../context/AdminAuthContext';
import { adminRolesApi } from '../api/endpoints';
import type { PermissionDto, RoleDto } from '../types';

function errorMessage(err: any, fallback: string) {
  return err?.response?.data?.message ?? fallback;
}

/** Inline name+description form shared by "create role" and "create permission" -- both use the
 *  same NAME_LIKE_THIS pattern the backend validates (^[A-Z][A-Z0-9_]{1,49}$). */
function CreateEntityForm({
  label, placeholder, onCancel, onSubmit, submitting, error,
}: {
  label: string;
  placeholder: string;
  onCancel: () => void;
  onSubmit: (name: string, description: string) => void;
  submitting: boolean;
  error: string | null;
}) {
  const [name, setName] = useState('');
  const [description, setDescription] = useState('');
  // Bug fix: both labels below were unassociated siblings of their inputs, not linked via
  // htmlFor/id -- a real axe "label" violation, same class fixed on Login.tsx.
  const fieldId = useId();

  return (
    <FormPanel
      title={`New ${label}`}
      onCancel={onCancel}
      onSubmit={(e) => {
        e.preventDefault();
        onSubmit(name.trim().toUpperCase(), description.trim());
      }}
      error={error}
      submitting={submitting}
      submitLabel="Create"
    >
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <label htmlFor={`${fieldId}-name`} className="text-xs font-medium text-muted mb-1 block">Name</label>
          <input
            id={`${fieldId}-name`}
            required
            placeholder={placeholder}
            value={name}
            onChange={(e) => setName(e.target.value)}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm font-mono uppercase"
          />
        </div>
        <div>
          <label htmlFor={`${fieldId}-description`} className="text-xs font-medium text-muted mb-1 block">Description</label>
          <input
            id={`${fieldId}-description`}
            required
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="w-full bg-bg border border-border rounded-lg px-3 py-2 text-sm"
          />
        </div>
      </div>
    </FormPanel>
  );
}

function RoleCard({
  role, allPermissions, canManageRoles, canManagePermissions,
}: {
  role: RoleDto;
  allPermissions: PermissionDto[];
  canManageRoles: boolean;
  canManagePermissions: boolean;
}) {
  const queryClient = useQueryClient();
  const [editingDescription, setEditingDescription] = useState(false);
  const [description, setDescription] = useState(role.description);
  const [selectedPermission, setSelectedPermission] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [confirmDeleteRole, setConfirmDeleteRole] = useState(false);
  const [confirmRevokePermission, setConfirmRevokePermission] = useState<PermissionDto | null>(null);

  function invalidate() {
    void queryClient.invalidateQueries({ queryKey: ['admin-roles'] });
  }

  const updateMutation = useMutation({
    mutationFn: () => adminRolesApi.updateRole(role.id, description.trim()),
    onSuccess: () => {
      setEditingDescription(false);
      invalidate();
    },
    onError: (err: any) => setError(errorMessage(err, 'Failed to update role.')),
  });
  const deleteMutation = useMutation({
    mutationFn: () => adminRolesApi.deleteRole(role.id),
    onSuccess: invalidate,
    onError: (err: any) => setError(errorMessage(err, 'Failed to delete role — it may still be assigned to a user.')),
  });
  const grantMutation = useMutation({
    mutationFn: (permissionId: string) => adminRolesApi.grantPermission(role.id, permissionId),
    onSuccess: () => {
      setSelectedPermission('');
      invalidate();
    },
    onError: (err: any) => setError(errorMessage(err, 'Failed to grant permission.')),
  });
  const revokeMutation = useMutation({
    mutationFn: (permissionId: string) => adminRolesApi.revokePermission(role.id, permissionId),
    onSuccess: invalidate,
    onError: (err: any) => setError(errorMessage(err, 'Failed to revoke permission.')),
  });

  const grantedIds = new Set(role.permissions.map((p) => p.id));
  const grantableePermissions = canManagePermissions ? allPermissions.filter((p) => !grantedIds.has(p.id)) : [];

  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card p-5">
      <div className="flex items-start justify-between mb-1.5">
        <div className="flex items-center gap-2">
          <ShieldCheck size={16} className="text-primary" />
          <h3 className="font-semibold text-ink">{role.name}</h3>
        </div>
        {canManageRoles && (
          <div className="flex items-center gap-1">
            <button
              type="button"
              title="Edit description"
              onClick={() => setEditingDescription((v) => !v)}
              className="w-7 h-7 rounded-lg hover:bg-bg text-muted hover:text-ink inline-flex items-center justify-center"
            >
              <Pencil size={13} />
            </button>
            <button
              type="button"
              title="Delete role"
              disabled={deleteMutation.isPending}
              onClick={() => setConfirmDeleteRole(true)}
              className="w-7 h-7 rounded-lg hover:bg-danger-bg text-muted hover:text-danger inline-flex items-center justify-center"
            >
              <Trash2 size={13} />
            </button>
          </div>
        )}
      </div>

      {error && <p className="text-xs text-danger bg-danger-bg rounded-lg px-2.5 py-1.5 mb-2">{error}</p>}

      {editingDescription ? (
        <div className="flex items-center gap-1.5 mb-3">
          <input
            value={description}
            onChange={(e) => setDescription(e.target.value)}
            className="flex-1 bg-bg border border-border rounded-lg px-2.5 py-1.5 text-xs"
          />
          <button
            type="button"
            disabled={updateMutation.isPending}
            onClick={() => updateMutation.mutate()}
            className="text-xs font-semibold text-primary px-2 py-1.5"
          >
            Save
          </button>
        </div>
      ) : (
        <p className="text-xs text-muted mb-3">{role.description}</p>
      )}

      <div className="flex flex-wrap gap-1.5 mb-3">
        {role.permissions.length === 0 && <span className="text-xs text-muted">No permissions granted.</span>}
        {role.permissions.map((p) => (
          <span
            key={p.id}
            title={p.description}
            className="inline-flex items-center gap-1.5 text-[11px] bg-bg border border-border rounded-full pl-2.5 pr-1 py-0.5 text-muted"
          >
            {p.name}
            {canManageRoles && (
              <button
                type="button"
                title="Revoke"
                disabled={revokeMutation.isPending}
                // This file held both patterns and had them backwards. Deleting a permission
                // (below) confirmed, even though the backend refuses it outright while any role
                // still grants it. Revoking one from a role did not -- and that succeeds
                // immediately and changes what every user holding this role can do. The more
                // consequential action was the unguarded one, behind a 3.5-unit "x".
                onClick={() => setConfirmRevokePermission(p)}
                className="w-3.5 h-3.5 rounded-full bg-border hover:bg-danger hover:text-white text-[9px] flex items-center justify-center"
              >
                ×
              </button>
            )}
          </span>
        ))}
      </div>

      {canManageRoles && grantableePermissions.length > 0 && (
        <div className="flex items-center gap-1.5 pt-3 border-t border-border">
          <select
            aria-label="Grant a permission"
            value={selectedPermission}
            onChange={(e) => setSelectedPermission(e.target.value)}
            className="flex-1 bg-bg border border-border rounded-lg px-2 py-1.5 text-xs"
          >
            <option value="">Grant a permission…</option>
            {grantableePermissions.map((p) => (
              <option key={p.id} value={p.id}>{p.name}</option>
            ))}
          </select>
          <button
            type="button"
            disabled={!selectedPermission || grantMutation.isPending}
            onClick={() => grantMutation.mutate(selectedPermission)}
            className="text-xs font-semibold text-on-primary bg-primary hover:bg-primary-dark rounded-lg px-2.5 py-1.5 disabled:opacity-50"
          >
            Grant
          </button>
        </div>
      )}

      {confirmDeleteRole && (
        <ConfirmDialog
          title={`Delete role ${role.name}?`}
          message="This only works if no account currently holds it."
          confirmLabel="Delete"
          danger
          busy={deleteMutation.isPending}
          onConfirm={() => { setConfirmDeleteRole(false); deleteMutation.mutate(); }}
          onCancel={() => setConfirmDeleteRole(false)}
        />
      )}

      {confirmRevokePermission && (
        <ConfirmDialog
          title={`Revoke "${confirmRevokePermission.name}" from ${role.name}?`}
          message="Every user with this role loses it immediately."
          confirmLabel="Revoke"
          danger
          busy={revokeMutation.isPending}
          onConfirm={() => {
            const permissionId = confirmRevokePermission.id;
            setConfirmRevokePermission(null);
            revokeMutation.mutate(permissionId);
          }}
          onCancel={() => setConfirmRevokePermission(null)}
        />
      )}
    </div>
  );
}

function RolesContent() {
  const { hasPermission } = useAdminAuth();
  const canManageRoles = hasPermission('ROLE_MANAGE');
  const canManagePermissions = hasPermission('PERMISSION_MANAGE');
  const queryClient = useQueryClient();

  const [showCreateRole, setShowCreateRole] = useState(false);
  const [showCreatePermission, setShowCreatePermission] = useState(false);
  const [createRoleError, setCreateRoleError] = useState<string | null>(null);
  const [createPermissionError, setCreatePermissionError] = useState<string | null>(null);
  const [confirmDeletePermission, setConfirmDeletePermission] = useState<PermissionDto | null>(null);

  const { data: roles, isLoading: rolesLoading } = useQuery({
    queryKey: ['admin-roles'],
    queryFn: () => adminRolesApi.listRoles(),
  });
  const { data: permissions, isLoading: permissionsLoading } = useQuery({
    queryKey: ['admin-permissions'],
    queryFn: () => adminRolesApi.listPermissions(),
    enabled: canManagePermissions,
  });

  const createRoleMutation = useMutation({
    mutationFn: ({ name, description }: { name: string; description: string }) => adminRolesApi.createRole(name, description),
    onSuccess: () => {
      setShowCreateRole(false);
      setCreateRoleError(null);
      void queryClient.invalidateQueries({ queryKey: ['admin-roles'] });
    },
    onError: (err: any) => setCreateRoleError(errorMessage(err, 'Failed to create role.')),
  });
  const createPermissionMutation = useMutation({
    mutationFn: ({ name, description }: { name: string; description: string }) => adminRolesApi.createPermission(name, description),
    onSuccess: () => {
      setShowCreatePermission(false);
      setCreatePermissionError(null);
      void queryClient.invalidateQueries({ queryKey: ['admin-permissions'] });
    },
    onError: (err: any) => setCreatePermissionError(errorMessage(err, 'Failed to create permission.')),
  });
  const deletePermissionMutation = useMutation({
    mutationFn: (id: string) => adminRolesApi.deletePermission(id),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['admin-permissions'] });
      void queryClient.invalidateQueries({ queryKey: ['admin-roles'] });
    },
  });

  if (rolesLoading || permissionsLoading) return <p className="text-muted text-sm">Loading…</p>;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm text-muted">
          Roles are database-backed groupings of permissions. Grant or revoke a role for a specific
          account from that account's page in <span className="text-ink font-medium">Users</span>.
        </p>
        {canManageRoles && !showCreateRole && (
          <button
            type="button"
            onClick={() => {
              setShowCreateRole(true);
              setCreateRoleError(null);
            }}
            className="inline-flex items-center gap-1.5 bg-primary hover:bg-primary-dark text-on-primary text-sm font-semibold rounded-lg px-4 py-2.5 flex-shrink-0"
          >
            <Plus size={15} /> New role
          </button>
        )}
      </div>

      {showCreateRole && (
        <CreateEntityForm
          label="role"
          placeholder="e.g. SUPPORT_AGENT"
          submitting={createRoleMutation.isPending}
          error={createRoleError}
          onCancel={() => {
            setShowCreateRole(false);
            setCreateRoleError(null);
          }}
          onSubmit={(name, description) => createRoleMutation.mutate({ name, description })}
        />
      )}

      <div className="grid gap-4 md:grid-cols-2 xl:grid-cols-3">
        {(roles ?? []).map((role) => (
          <RoleCard
            key={role.id}
            role={role}
            allPermissions={permissions ?? []}
            canManageRoles={canManageRoles}
            canManagePermissions={canManagePermissions}
          />
        ))}
      </div>

      {canManagePermissions && (
        <div className="bg-card border border-border rounded-xl2 shadow-card p-5">
          <div className="flex items-center justify-between mb-3">
            <h3 className="font-semibold text-ink">All permissions</h3>
            {!showCreatePermission && (
              <button
                type="button"
                onClick={() => {
                  setShowCreatePermission(true);
                  setCreatePermissionError(null);
                }}
                className="inline-flex items-center gap-1.5 text-xs font-semibold text-primary hover:bg-bg rounded-lg px-2.5 py-1.5"
              >
                <Plus size={13} /> New permission
              </button>
            )}
          </div>

          {showCreatePermission && (
            <div className="mb-4">
              <CreateEntityForm
                label="permission"
                placeholder="e.g. REPORT_EXPORT"
                submitting={createPermissionMutation.isPending}
                error={createPermissionError}
                onCancel={() => {
                  setShowCreatePermission(false);
                  setCreatePermissionError(null);
                }}
                onSubmit={(name, description) => createPermissionMutation.mutate({ name, description })}
              />
            </div>
          )}

          <div className="grid gap-2 md:grid-cols-2">
            {(permissions ?? []).map((p) => (
              <div key={p.id} className="flex items-center justify-between text-sm gap-2">
                <div className="min-w-0">
                  <span className="font-medium text-ink">{p.name}</span>
                  <span className="text-muted"> — {p.description}</span>
                </div>
                <button
                  type="button"
                  title="Delete permission"
                  disabled={deletePermissionMutation.isPending}
                  onClick={() => setConfirmDeletePermission(p)}
                  className="w-6 h-6 rounded-lg hover:bg-danger-bg text-muted hover:text-danger inline-flex items-center justify-center flex-shrink-0"
                >
                  <Trash2 size={12} />
                </button>
              </div>
            ))}
          </div>
          {deletePermissionMutation.isError && (
            <p className="text-xs text-danger mt-2">
              {errorMessage(deletePermissionMutation.error, 'Could not delete this permission — it may still be granted to a role.')}
            </p>
          )}
        </div>
      )}

      {confirmDeletePermission && (
        <ConfirmDialog
          title={`Delete permission ${confirmDeletePermission.name}?`}
          message="This only works if no role currently grants it."
          confirmLabel="Delete"
          danger
          busy={deletePermissionMutation.isPending}
          onConfirm={() => {
            const id = confirmDeletePermission.id;
            setConfirmDeletePermission(null);
            deletePermissionMutation.mutate(id);
          }}
          onCancel={() => setConfirmDeletePermission(null)}
        />
      )}
    </div>
  );
}

export default function Roles() {
  return (
    <AdminLayout title="Roles & Permissions" subtitle="What each role grants across the platform">
      <RequirePermission permission="ROLE_MANAGE">
        <RolesContent />
      </RequirePermission>
    </AdminLayout>
  );
}
