import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';

// Sidebar.visibleLinks filters the nav purely off `permissions` + `fullName` + `logout` from
// useAdminAuth -- mocking the hook lets this test drive that filtering directly instead of
// standing up a real AdminAuthProvider (and the login flow it depends on).
vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));

function renderSidebar(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    fullName: 'Support Admin',
    permissions,
    logout: vi.fn(),
  }));

  return render(
    <MemoryRouter>
      <Sidebar />
    </MemoryRouter>
  );
}

describe('Sidebar', () => {
  beforeEach(() => {
    vi.mocked(useAdminAuth).mockReset();
  });

  it('always shows Dashboard, which requires no permission', () => {
    renderSidebar([]);
    expect(screen.getByText('Dashboard')).toBeInTheDocument();
  });

  it('hides every permission-gated section for an account with no admin permissions', () => {
    renderSidebar([]);

    expect(screen.queryByText('Users')).not.toBeInTheDocument();
    expect(screen.queryByText('Roles & Permissions')).not.toBeInTheDocument();
    expect(screen.queryByText('Banks')).not.toBeInTheDocument();
    expect(screen.queryByText('Merchant Intelligence')).not.toBeInTheDocument();
    expect(screen.queryByText('Global Rules')).not.toBeInTheDocument();
    expect(screen.queryByText('Learning Engine')).not.toBeInTheDocument();
    expect(screen.queryByText('Reconciliation Monitor')).not.toBeInTheDocument();
    expect(screen.queryByText('Platform Analytics')).not.toBeInTheDocument();
    expect(screen.queryByText('Audit Log')).not.toBeInTheDocument();
    expect(screen.queryByText('System Health')).not.toBeInTheDocument();
    expect(screen.queryByText('Settings')).not.toBeInTheDocument();
  });

  it('shows only the sections a narrowly-scoped role (AUDIT_VIEW only) actually holds', () => {
    renderSidebar(['AUDIT_VIEW']);

    expect(screen.getByText('Dashboard')).toBeInTheDocument();
    expect(screen.getByText('Audit Log')).toBeInTheDocument();
    expect(screen.queryByText('Users')).not.toBeInTheDocument();
    expect(screen.queryByText('Roles & Permissions')).not.toBeInTheDocument();
    expect(screen.queryByText('Global Rules')).not.toBeInTheDocument();
  });

  it('shows Global Rules only when the account holds RULE_MANAGE', () => {
    renderSidebar(['RULE_MANAGE']);

    expect(screen.getByText('Global Rules')).toBeInTheDocument();
    expect(screen.queryByText('Banks')).not.toBeInTheDocument();
  });

  // MERCHANT_MANAGE gates two separate sidebar entries (Merchant Intelligence and Learning
  // Engine, see AdminLearningStatsController's class comment for why Learning Engine reuses this
  // permission rather than minting its own) -- both must show together, and neither leaks into
  // an account that only holds a different, unrelated permission.
  it('shows Merchant Intelligence and Learning Engine together when the account holds MERCHANT_MANAGE', () => {
    renderSidebar(['MERCHANT_MANAGE']);

    expect(screen.getByText('Merchant Intelligence')).toBeInTheDocument();
    expect(screen.getByText('Learning Engine')).toBeInTheDocument();
    expect(screen.queryByText('Reconciliation Monitor')).not.toBeInTheDocument();
    expect(screen.queryByText('Platform Analytics')).not.toBeInTheDocument();
  });

  it('shows Reconciliation Monitor only when the account holds RECONCILIATION_VIEW', () => {
    renderSidebar(['RECONCILIATION_VIEW']);

    expect(screen.getByText('Reconciliation Monitor')).toBeInTheDocument();
    expect(screen.queryByText('Platform Analytics')).not.toBeInTheDocument();
    expect(screen.queryByText('Learning Engine')).not.toBeInTheDocument();
  });

  it('shows Platform Analytics only when the account holds PLATFORM_ANALYTICS_VIEW', () => {
    renderSidebar(['PLATFORM_ANALYTICS_VIEW']);

    expect(screen.getByText('Platform Analytics')).toBeInTheDocument();
    expect(screen.queryByText('Reconciliation Monitor')).not.toBeInTheDocument();
  });

  it('shows every section for an account holding every gating permission', () => {
    renderSidebar([
      'USER_VIEW', 'ROLE_MANAGE', 'BANK_MANAGE', 'MERCHANT_MANAGE', 'RULE_MANAGE',
      'RECONCILIATION_VIEW', 'PLATFORM_ANALYTICS_VIEW', 'AUDIT_VIEW', 'SYSTEM_SETTINGS',
    ]);

    for (const label of ['Dashboard', 'Users', 'Roles & Permissions', 'Banks', 'Merchant Intelligence',
      'Global Rules', 'Learning Engine', 'Reconciliation Monitor', 'Platform Analytics',
      'Audit Log', 'System Health', 'Settings']) {
      expect(screen.getByText(label)).toBeInTheDocument();
    }
  });

  it('renders the account name from useAdminAuth', () => {
    renderSidebar([]);
    expect(screen.getByText('Support Admin')).toBeInTheDocument();
  });
});
