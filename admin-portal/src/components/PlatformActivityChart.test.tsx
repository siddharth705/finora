import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PlatformActivityChart } from './PlatformActivityChart';
import type { ActivityTrendPointDto } from '../types';

const data: ActivityTrendPointDto[] = [
  { date: '2026-08-16', signups: 1, imports: 2, transactions: 10 },
  { date: '2026-08-17', signups: 0, imports: 1, transactions: 8 },
  { date: '2026-08-18', signups: 3, imports: 4, transactions: 15 },
  { date: '2026-08-19', signups: 2, imports: 2, transactions: 12 },
  { date: '2026-08-20', signups: 1, imports: 3, transactions: 9 },
  { date: '2026-08-21', signups: 0, imports: 0, transactions: 5 },
  { date: '2026-08-22', signups: 4, imports: 5, transactions: 20 },
];

describe('PlatformActivityChart', () => {
  it('renders one line series per metric, with a legend naming all three', () => {
    const { container } = render(<PlatformActivityChart data={data} />);

    expect(screen.getByText('Transactions')).toBeInTheDocument();
    expect(screen.getByText('Imports')).toBeInTheDocument();
    expect(screen.getByText('Signups')).toBeInTheDocument();
    expect(container.querySelectorAll('.recharts-line')).toHaveLength(3);
  });

  it('formats x-axis ticks as short calendar days, not raw ISO dates', () => {
    render(<PlatformActivityChart data={data} />);

    // The most recent day is always kept even if recharts thins out the others for space.
    expect(screen.getByText('Aug 22')).toBeInTheDocument();
    expect(screen.queryByText('2026-08-22')).not.toBeInTheDocument();
  });
});
