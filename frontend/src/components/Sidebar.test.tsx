import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { Sidebar } from './Sidebar';
import { AuthProvider } from '../context/AuthContext';

// Covers only the collapse toggle this session added -- Sidebar's own nav/logout/account-menu
// behavior predates this and isn't the subject of this change.
function renderSidebar() {
  return render(
    <MemoryRouter>
      <AuthProvider>
        <Sidebar />
      </AuthProvider>
    </MemoryRouter>
  );
}

describe('Sidebar — collapse toggle', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('starts expanded with nav labels visible when nothing is stored', () => {
    renderSidebar();

    expect(screen.getByText('Dashboard')).toBeInTheDocument();
    expect(screen.getByText('FYNORA')).toBeInTheDocument();
  });

  it('hides nav labels and the wordmark, but keeps icons reachable by title, once collapsed', async () => {
    const user = userEvent.setup();
    renderSidebar();

    await user.click(screen.getByRole('button', { name: /collapse sidebar/i }));

    expect(screen.queryByText('Dashboard')).not.toBeInTheDocument();
    expect(screen.queryByText('FYNORA')).not.toBeInTheDocument();
    // The nav link itself is still there and still reachable -- title carries the label as a
    // tooltip so a collapsed item isn't identifiable by icon shape alone.
    expect(screen.getByTitle('Dashboard')).toBeInTheDocument();
  });

  it('expands again on a second click, restoring the labels', async () => {
    const user = userEvent.setup();
    renderSidebar();
    await user.click(screen.getByRole('button', { name: /collapse sidebar/i }));

    await user.click(screen.getByRole('button', { name: /expand sidebar/i }));

    expect(screen.getByText('Dashboard')).toBeInTheDocument();
  });

  it('persists the collapsed state across a remount, so it survives a reload', async () => {
    const user = userEvent.setup();
    const { unmount } = renderSidebar();
    await user.click(screen.getByRole('button', { name: /collapse sidebar/i }));
    unmount();

    renderSidebar();

    expect(screen.queryByText('Dashboard')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: /expand sidebar/i })).toBeInTheDocument();
  });
});

describe('Sidebar — nav active-state matching', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  // '/app/reports/advanced' is a nested child route of '/app/reports' -- without `end` on the
  // Reports link, NavLink's default prefix matching highlighted BOTH links whenever Advanced
  // Reports was the active page (found live: Reports got the active background too, alongside
  // Advanced Reports' own focus ring).
  it('highlights only Advanced Reports, not Reports, when Advanced Reports is the active route', () => {
    render(
      <MemoryRouter initialEntries={['/app/reports/advanced']}>
        <AuthProvider>
          <Sidebar />
        </AuthProvider>
      </MemoryRouter>
    );

    const reportsLink = screen.getByText('Reports').closest('a');
    const advancedReportsLink = screen.getByText('Advanced Reports').closest('a');

    expect(advancedReportsLink?.className).toContain('bg-[#F4F1EC]');
    expect(reportsLink?.className).not.toContain('bg-[#F4F1EC]');
  });

  it('still highlights Reports when Reports itself is the active route', () => {
    render(
      <MemoryRouter initialEntries={['/app/reports']}>
        <AuthProvider>
          <Sidebar />
        </AuthProvider>
      </MemoryRouter>
    );

    expect(screen.getByText('Reports').closest('a')?.className).toContain('bg-[#F4F1EC]');
  });
});
