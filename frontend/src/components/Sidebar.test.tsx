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
    expect(screen.getByText('FINORA')).toBeInTheDocument();
  });

  it('hides nav labels and the wordmark, but keeps icons reachable by title, once collapsed', async () => {
    const user = userEvent.setup();
    renderSidebar();

    await user.click(screen.getByRole('button', { name: /collapse sidebar/i }));

    expect(screen.queryByText('Dashboard')).not.toBeInTheDocument();
    expect(screen.queryByText('FINORA')).not.toBeInTheDocument();
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
