import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SectionHeader } from './SectionHeader';

function renderHeader(props: { title: string; viewAllTo?: string }) {
  return render(<MemoryRouter><SectionHeader {...props} /></MemoryRouter>);
}

describe('SectionHeader', () => {
  it('renders the title', () => {
    renderHeader({ title: 'Cash Flow Overview' });
    expect(screen.getByRole('heading', { name: 'Cash Flow Overview' })).toBeInTheDocument();
  });

  it('renders no "View All" link when viewAllTo is not given', () => {
    renderHeader({ title: 'Category Breakdown' });
    expect(screen.queryByRole('link', { name: /view all/i })).not.toBeInTheDocument();
  });

  it('renders a "View All" link pointing at viewAllTo when given', () => {
    renderHeader({ title: 'Recent Transactions', viewAllTo: '/app/transactions' });
    expect(screen.getByRole('link', { name: /view all/i })).toHaveAttribute('href', '/app/transactions');
  });
});
