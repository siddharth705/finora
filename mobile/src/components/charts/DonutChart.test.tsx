import { fireEvent, render, screen } from '@testing-library/react-native';
import { DonutChart, type Slice } from './DonutChart';

/**
 * Track C/C4: the legend is this chart's own drill-through into the Ledger. Focused on the legend
 * rather than the SVG arcs -- the arcs' own geometry is chartGeometry.test.ts's job, and neither
 * arc nor legend rendering changes based on whether a press handler is wired.
 */

const slice = (over: Partial<Slice> = {}): Slice => ({
  label: 'Dining', value: 4000, color: '#3b82f6', drillable: true, ...over,
});

describe('DonutChart legend', () => {
  it('renders a drillable row as a real button that reports its own label when pressed', () => {
    const onSlicePress = jest.fn();
    render(<DonutChart slices={[slice({ label: 'Dining' })]} onSlicePress={onSlicePress} />);

    const row = screen.getByRole('button', { name: 'Dining: ₹4,000' });
    fireEvent.press(row);

    expect(onSlicePress).toHaveBeenCalledWith('Dining');
  });

  // The synthetic "Other" overflow bucket (and a real category folded into it -- see
  // chartGeometry.test.ts) has no single category a tap on it could honestly mean.
  it('renders a non-drillable row as plain, unpressable text', () => {
    const onSlicePress = jest.fn();
    render(<DonutChart slices={[slice({ label: 'Other', drillable: false })]} onSlicePress={onSlicePress} />);

    expect(screen.queryByRole('button', { name: 'Other: ₹4,000' })).toBeNull();
    // Still legible and still accessible -- just not a navigation target.
    expect(screen.getByText('Other')).toBeTruthy();
  });

  // A caller that never offers a drill-through (InvestmentsScreen's holdings donut) must keep
  // rendering exactly as it did before this feature existed -- drillable:true on a slice means
  // nothing without a handler to call.
  it('stays untappable when the caller passes no onSlicePress at all, even for a drillable slice', () => {
    render(<DonutChart slices={[slice({ label: 'Equity', drillable: true })]} />);

    expect(screen.queryByRole('button', { name: 'Equity: ₹4,000' })).toBeNull();
    expect(screen.getByText('Equity')).toBeTruthy();
  });
});
