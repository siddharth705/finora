import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { UploadCloud } from 'lucide-react';
import { QuickActionCard } from './QuickActionCard';

describe('QuickActionCard', () => {
  it('renders as a link when "to" is given', () => {
    render(<MemoryRouter><QuickActionCard icon={UploadCloud} label="Import Statement" to="/app/import" /></MemoryRouter>);
    expect(screen.getByRole('link', { name: /import statement/i })).toHaveAttribute('href', '/app/import');
  });

  it('renders as a button and calls onClick when "to" is not given', async () => {
    const onClick = vi.fn();
    const user = userEvent.setup();
    render(<MemoryRouter><QuickActionCard icon={UploadCloud} label="Add Transaction" onClick={onClick} /></MemoryRouter>);

    const button = screen.getByRole('button', { name: /add transaction/i });
    await user.click(button);

    expect(onClick).toHaveBeenCalledOnce();
  });
});
