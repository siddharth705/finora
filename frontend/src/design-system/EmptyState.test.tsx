import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Wallet } from 'lucide-react';
import { EmptyState } from './EmptyState';

describe('EmptyState', () => {
  it('renders the title, description, and cta', () => {
    render(
      <EmptyState
        icon={Wallet}
        iconBg="bg-blue-100"
        iconColor="text-blue-600"
        title="No accounts yet"
        desc="Add your bank accounts to get a complete view."
        cta={<button type="button">+ Add Account</button>}
      />
    );
    expect(screen.getByText('No accounts yet')).toBeInTheDocument();
    expect(screen.getByText('Add your bank accounts to get a complete view.')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: '+ Add Account' })).toBeInTheDocument();
  });

  it('renders with no cta when none is given, for empty states with nothing actionable to do', () => {
    render(<EmptyState icon={Wallet} iconBg="bg-blue-100" iconColor="text-blue-600" title="No expenses recorded" desc="Nothing was spent this month." />);
    expect(screen.getByText('No expenses recorded')).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });
});
