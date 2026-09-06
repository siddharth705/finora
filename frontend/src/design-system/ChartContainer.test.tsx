import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
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

  describe('the delayed skeleton', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('announces loading immediately, via the accessible label, without waiting for the skeleton delay', () => {
      // The sr-only label is what a screen reader announces -- it must not wait out the same
      // flash-prevention window the visual skeleton does, since that window exists to avoid a
      // sighted-user flicker, not to delay an announcement.
      render(<ChartContainer loading loadingLabel="Loading trend…"><p>the chart</p></ChartContainer>);
      const region = screen.getByRole('status');
      expect(region).toHaveAttribute('aria-busy', 'true');
      expect(screen.getByText('Loading trend…')).toBeInTheDocument();
    });

    it('does not render a visible skeleton shape before the delay window elapses', () => {
      const { container } = render(<ChartContainer loading loadingLabel="Loading…"><p>the chart</p></ChartContainer>);
      expect(container.querySelectorAll('.animate-pulse').length).toBe(0);
    });

    it('renders the skeleton chart shape once the delay window elapses', () => {
      const { container } = render(<ChartContainer loading loadingLabel="Loading…"><p>the chart</p></ChartContainer>);
      act(() => {
        vi.advanceTimersByTime(250);
      });
      expect(container.querySelectorAll('.animate-pulse').length).toBeGreaterThan(0);
    });
  });
});
