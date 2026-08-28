import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { CategoryCreateEditPanel } from './CategoryCreateEditPanel';
import { categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  categoriesApi: { options: vi.fn(), create: vi.fn(), update: vi.fn() },
}));

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

    render(
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
    render(<CategoryCreateEditPanel mode="create" initialName="" onSaved={vi.fn()} onCancel={vi.fn()} />);
    await screen.findByText('Tag');

    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(categoriesApi.create).not.toHaveBeenCalled();
    expect(screen.getByText(/name/i)).toBeInTheDocument();
  });
});
