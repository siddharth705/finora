import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactElement } from 'react';
import { CategoryCreateEditPanel } from './CategoryCreateEditPanel';
import { categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  categoriesApi: { options: vi.fn(), create: vi.fn(), update: vi.fn() },
}));

// Both this component and the one it renders read/invalidate the shared ['categories'] react-query
// cache, so a provider is required. Fresh client per render keeps tests isolated.
function renderWithClient(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe('CategoryCreateEditPanel', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(categoriesApi.options).mockResolvedValue({
      icons: [{ token: 'tag', label: 'Tag' }, { token: 'home', label: 'Home' }],
      colors: [{ token: 'gray', label: '#6b7280' }, { token: 'blue', label: '#2563eb' }],
    });
  });

  it('creates a category with the chosen name, icon, and color', async () => {
    const user = userEvent.setup();
    vi.mocked(categoriesApi.create).mockResolvedValue({
      id: '1', name: 'SIP', isSystem: false, icon: 'home', color: 'blue',
    });
    const onSaved = vi.fn();

    renderWithClient(
      <CategoryCreateEditPanel mode="create" initialName="SIP" onSaved={onSaved} onCancel={vi.fn()} />,
    );

    await screen.findByText('Home');
    await user.click(screen.getByText('Home'));
    await user.click(screen.getByRole('button', { name: 'blue' }));
    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(categoriesApi.create).toHaveBeenCalledWith('SIP', 'home', 'blue');
    expect(onSaved).toHaveBeenCalledWith({ id: '1', name: 'SIP', isSystem: false, icon: 'home', color: 'blue' });
  });

  it('rejects saving a blank name', async () => {
    const user = userEvent.setup();
    renderWithClient(<CategoryCreateEditPanel mode="create" initialName="" onSaved={vi.fn()} onCancel={vi.fn()} />);
    await screen.findByText('Tag');

    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(categoriesApi.create).not.toHaveBeenCalled();
    expect(screen.getByText(/name/i)).toBeInTheDocument();
  });
});
