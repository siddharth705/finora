import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ChartContainer } from './ChartContainer';

describe('ChartContainer', () => {
  it('renders children when neither loading nor empty', () => {
    render(<ChartContainer><p>the chart</p></ChartContainer>);
    expect(screen.getByText('the chart')).toBeInTheDocument();
  });

  it('renders a loading message instead of children while loading', () => {
    render(<ChartContainer loading loadingLabel="Loading trend…"><p>the chart</p></ChartContainer>);
    expect(screen.getByText('Loading trend…')).toBeInTheDocument();
    expect(screen.queryByText('the chart')).not.toBeInTheDocument();
  });

  it('renders the empty state instead of children when isEmpty', () => {
    render(<ChartContainer isEmpty emptyState={<p>No data yet</p>}><p>the chart</p></ChartContainer>);
    expect(screen.getByText('No data yet')).toBeInTheDocument();
    expect(screen.queryByText('the chart')).not.toBeInTheDocument();
  });

  it('prefers the loading state over the empty state when both are true', () => {
    render(<ChartContainer loading isEmpty emptyState={<p>No data yet</p>} loadingLabel="Loading…"><p>the chart</p></ChartContainer>);
    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText('No data yet')).not.toBeInTheDocument();
  });
});
