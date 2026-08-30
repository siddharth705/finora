import { render, screen } from '@testing-library/react-native';
import { SkeletonCard, SkeletonTransactionRow } from './Skeletons';
import { ThemeProvider } from '../../theme';

function withTheme(node: React.ReactElement) {
  return render(<ThemeProvider>{node}</ThemeProvider>);
}

// See Shimmer.test.tsx's own note: shimmer-block/skeleton-* elements are deliberately hidden from
// accessibility, so every query here needs { hidden: true } to find them.

describe('SkeletonCard', () => {
  it('renders a heading placeholder plus the requested number of line placeholders', () => {
    withTheme(<SkeletonCard lines={2} />);
    // heading + 2 lines = 3 shimmer blocks
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3);
  });

  it('defaults to 3 lines when none is given', () => {
    withTheme(<SkeletonCard />);
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(4);
  });
});

describe('SkeletonTransactionRow', () => {
  it('renders the row shape: description, meta and amount placeholders', () => {
    withTheme(<SkeletonTransactionRow />);
    expect(screen.getByTestId('skeleton-transaction-row', { hidden: true })).toBeTruthy();
    expect(screen.getAllByTestId('shimmer-block', { hidden: true })).toHaveLength(3);
  });
});
