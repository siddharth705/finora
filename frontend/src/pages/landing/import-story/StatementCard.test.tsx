import { render } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { StatementCard } from './StatementCard';

describe('StatementCard', () => {
  it('renders realistic statement rows, not generic file icons', () => {
    const { getByText } = render(<StatementCard />);
    expect(getByText('Amazon Pay')).toBeInTheDocument();
    expect(getByText('Salary credit')).toBeInTheDocument();
  });

  it('names no real bank -- this is an illustration, not a partnership claim', () => {
    const { getByText, queryByText } = render(<StatementCard />);
    expect(getByText('Bank statement')).toBeInTheDocument();
    expect(queryByText(/HDFC|ICICI|SBI|Axis Bank|Kotak/i)).not.toBeInTheDocument();
  });

  it('shows no scan-line band when not scanning', () => {
    const { container } = render(<StatementCard scanning={false} />);
    expect(container.querySelector('[aria-hidden="true"]')).not.toBeInTheDocument();
  });

  it('shows the scan-line band while scanning', () => {
    const { container } = render(<StatementCard scanning={true} />);
    expect(container.querySelector('[aria-hidden="true"]')).toBeInTheDocument();
  });
});
