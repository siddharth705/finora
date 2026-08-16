import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Wallet } from 'lucide-react';
import { MetricCard } from './MetricCard';

describe('MetricCard', () => {
  it('renders the label and value', () => {
    render(<MetricCard label="Total Balance" value="₹1,000" icon={Wallet} iconBg="bg-blue-100" iconColor="text-blue-600" />);
    expect(screen.getByText('Total Balance')).toBeInTheDocument();
    expect(screen.getByText('₹1,000')).toBeInTheDocument();
  });

  it('renders no delta line at all when the KPI has no delta concept (no deltaLabel given)', () => {
    render(<MetricCard label="Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600" />);
    expect(screen.queryByText(/vs last month/i)).not.toBeInTheDocument();
  });

  it('renders a muted placeholder when the KPI has a delta concept but no value yet', () => {
    render(<MetricCard label="Balance" value="₹500" icon={Wallet} iconBg="bg-blue-100" iconColor="text-blue-600" deltaLabel="vs last month" />);
    expect(screen.getByText('— vs last month')).toBeInTheDocument();
  });

  it('renders a green up arrow for a positive delta', () => {
    render(<MetricCard label="Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600" delta={12.3} deltaLabel="vs last month" />);
    const line = screen.getByText(/12\.3% vs last month/);
    expect(line).toHaveClass('text-success');
    expect(line.textContent).toContain('▲');
  });

  it('inverts the color for expense-like metrics where a negative delta is the good outcome', () => {
    render(<MetricCard label="Expenses" value="₹500" icon={Wallet} iconBg="bg-red-100" iconColor="text-red-600" delta={-5} deltaLabel="vs last month" invertDelta />);
    expect(screen.getByText(/5\.0% vs last month/)).toHaveClass('text-success');
  });

  it('applies a valueColor override for pages with no delta line to carry that meaning', () => {
    render(<MetricCard label="Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600" valueColor="text-success" />);
    expect(screen.getByText('₹500')).toHaveClass('text-success');
  });

  it('defaults the value color to text-ink when no valueColor is given', () => {
    render(<MetricCard label="Total Balance" value="₹1,000" icon={Wallet} iconBg="bg-blue-100" iconColor="text-blue-600" />);
    expect(screen.getByText('₹1,000')).toHaveClass('text-ink');
  });
});
