import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
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

  it('renders no "Why?" toggle for a muted placeholder that has no gate reason', () => {
    render(<MetricCard label="Balance" value="₹500" icon={Wallet} iconBg="bg-blue-100" iconColor="text-blue-600" deltaLabel="vs last month" />);
    expect(screen.queryByRole('button', { name: /why/i })).not.toBeInTheDocument();
  });

  it('renders a "Why?" toggle next to a muted placeholder that has a gate reason', () => {
    render(
      <MetricCard
        label="Total Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600"
        deltaLabel="vs last month" gateReasonText="Last month has fewer than 3 transactions, too few to compare reliably."
      />
    );
    expect(screen.getByRole('button', { name: 'Why?' })).toBeInTheDocument();
    expect(screen.queryByText(/fewer than 3 transactions/)).not.toBeInTheDocument();
  });

  it('reveals the gate reason text on clicking "Why?", and hides it again on a second click', async () => {
    const user = userEvent.setup();
    render(
      <MetricCard
        label="Total Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600"
        deltaLabel="vs last month" gateReasonText="Last month has fewer than 3 transactions, too few to compare reliably."
      />
    );

    await user.click(screen.getByRole('button', { name: 'Why?' }));
    expect(screen.getByText(/fewer than 3 transactions/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hide' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hide' }));
    expect(screen.queryByText(/fewer than 3 transactions/)).not.toBeInTheDocument();
  });

  it('never renders a "Why?" toggle once the delta is a real number, even if a gate reason is passed', () => {
    render(
      <MetricCard
        label="Total Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600"
        delta={12.3} deltaLabel="vs last month" gateReasonText="Last month has fewer than 3 transactions, too few to compare reliably."
      />
    );
    expect(screen.queryByRole('button', { name: /why/i })).not.toBeInTheDocument();
  });

  it('renders no "Why?" toggle on a real delta with no movers to explain it', () => {
    render(
      <MetricCard
        label="Total Expenses" value="₹5,000" icon={Wallet} iconBg="bg-red-100" iconColor="text-red-600"
        delta={12.3} deltaLabel="vs last month" moverLines={[]}
      />
    );
    expect(screen.queryByRole('button', { name: /why/i })).not.toBeInTheDocument();
  });

  it('renders a "Why?" toggle next to a real delta that has category movers behind it', () => {
    render(
      <MetricCard
        label="Total Expenses" value="₹8,000" icon={Wallet} iconBg="bg-red-100" iconColor="text-red-600"
        delta={60} deltaLabel="vs last month"
        moverLines={['Dining: ₹8,000 vs ₹5,000 (+60%)']}
      />
    );
    expect(screen.getByRole('button', { name: 'Why?' })).toBeInTheDocument();
    expect(screen.queryByText(/Dining/)).not.toBeInTheDocument();
  });

  it('reveals every mover line on clicking "Why?", and hides them again on a second click', async () => {
    const user = userEvent.setup();
    render(
      <MetricCard
        label="Total Expenses" value="₹8,000" icon={Wallet} iconBg="bg-red-100" iconColor="text-red-600"
        delta={60} deltaLabel="vs last month"
        moverLines={['Dining: ₹8,000 vs ₹5,000 (+60%)', 'Travel: ₹2,000 vs ₹1,000 (+100%)']}
      />
    );

    await user.click(screen.getByRole('button', { name: 'Why?' }));
    expect(screen.getByText('Dining: ₹8,000 vs ₹5,000 (+60%)')).toBeInTheDocument();
    expect(screen.getByText('Travel: ₹2,000 vs ₹1,000 (+100%)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hide' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hide' }));
    expect(screen.queryByText(/Dining/)).not.toBeInTheDocument();
  });

  it('variant="elevated" renders the delta as a colored pill instead of plain text', () => {
    render(
      <MetricCard
        label="Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600"
        delta={12.3} deltaLabel="vs last month" variant="elevated"
      />
    );
    const chip = screen.getByText('▲ 12.3%');
    expect(chip).toHaveClass('bg-success-bg', 'text-success');
    expect(screen.getByText('vs last month')).toBeInTheDocument();
  });

  it('variant="elevated" inverts the pill color for expense-like metrics', () => {
    render(
      <MetricCard
        label="Expenses" value="₹500" icon={Wallet} iconBg="bg-red-100" iconColor="text-red-600"
        delta={-5} deltaLabel="vs last month" invertDelta variant="elevated"
      />
    );
    expect(screen.getByText('▼ 5.0%')).toHaveClass('bg-success-bg', 'text-success');
  });

  it('variant="elevated" still supports the movers "Why?" disclosure', async () => {
    const user = userEvent.setup();
    render(
      <MetricCard
        label="Total Expenses" value="₹45,000" icon={Wallet} iconBg="bg-red-100" iconColor="text-red-600"
        delta={60} deltaLabel="vs last month" invertDelta variant="elevated"
        moverLines={['Dining: ₹8,000 vs ₹5,000 (+60%)']}
      />
    );
    await user.click(screen.getByRole('button', { name: 'Why?' }));
    expect(screen.getByText(/Dining: ₹8,000/)).toBeInTheDocument();
  });

  it('variant="elevated" never renders a "Why?" toggle on a real delta with no movers, even with a stale gate reason', () => {
    render(
      <MetricCard
        label="Total Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600"
        delta={12.3} deltaLabel="vs last month" variant="elevated"
        gateReasonText="Last month has fewer than 3 transactions, too few to compare reliably."
      />
    );
    expect(screen.queryByRole('button', { name: /why/i })).not.toBeInTheDocument();
  });

  it('variant="elevated" renders a muted placeholder (no pill) when there is no delta value yet', () => {
    render(
      <MetricCard
        label="Balance" value="₹500" icon={Wallet} iconBg="bg-blue-100" iconColor="text-blue-600"
        deltaLabel="vs last month" variant="elevated"
      />
    );
    expect(screen.getByText('— vs last month')).toBeInTheDocument();
  });

  it('variant="elevated" reveals a gate reason on the muted placeholder, and hides it again on a second click', async () => {
    const user = userEvent.setup();
    render(
      <MetricCard
        label="Total Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600"
        deltaLabel="vs last month" variant="elevated"
        gateReasonText="Last month has fewer than 3 transactions, too few to compare reliably."
      />
    );

    await user.click(screen.getByRole('button', { name: 'Why?' }));
    expect(screen.getByText(/fewer than 3 transactions/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Hide' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hide' }));
    expect(screen.queryByText(/fewer than 3 transactions/)).not.toBeInTheDocument();
  });

  it('defaults to the original plain-text delta line when no variant is given', () => {
    render(<MetricCard label="Income" value="₹500" icon={Wallet} iconBg="bg-green-100" iconColor="text-green-600" delta={12.3} deltaLabel="vs last month" />);
    const line = screen.getByText(/12\.3% vs last month/);
    expect(line).toHaveClass('text-success');
  });
});
