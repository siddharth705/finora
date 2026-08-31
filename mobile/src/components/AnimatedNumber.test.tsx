import { act, render, screen, waitFor } from '@testing-library/react-native';
import { AnimatedNumber } from './AnimatedNumber';

describe('AnimatedNumber', () => {
  it('renders the formatted currency for its initial value', async () => {
    render(<AnimatedNumber value={82000} testID="balance" />);
    await waitFor(() => {
      expect(screen.getByTestId('balance').props.defaultValue).toBe('₹82,000');
    });
  });

  it('formats a negative value with the sign before the symbol, same as fmtCurrency', async () => {
    render(<AnimatedNumber value={-500} testID="balance" />);
    await waitFor(() => {
      expect(screen.getByTestId('balance').props.defaultValue).toBe('-₹500');
    });
  });

  it('settles on the new formatted value when the prop changes', async () => {
    // Two things this test relies on that aren't obvious from the plain RNTL API:
    //
    // 1. Reanimated's jest-mode timing runs on fake timers, not real wall-clock time (its
    //    deprecated advanceAnimationByTime/withReanimatedTimer helpers both say so directly:
    //    "use jest.useFakeTimers()/jest.advanceTimersByTime directly") -- waitFor's real-time
    //    polling alone never observes a withTiming transition settle.
    // 2. useAnimatedProps updates a native prop directly, bypassing React's own prop/render
    //    cycle by design (see AnimatedNumber's own doc comment) -- so a post-mount update is
    //    genuinely invisible to `.props.defaultValue` on the RNTL query result even once the
    //    animation settles. Reanimated's own jest utilities track it separately
    //    (`jestAnimatedProps`), surfaced through the `toHaveAnimatedProps` matcher setUpTests()
    //    registers -- that's the one thing actually able to observe it under test.
    jest.useFakeTimers();
    const { rerender } = render(<AnimatedNumber value={1000} duration={50} testID="balance" />);
    expect(screen.getByTestId('balance').props.defaultValue).toBe('₹1,000');

    rerender(<AnimatedNumber value={2000} duration={50} testID="balance" />);
    act(() => {
      jest.advanceTimersByTime(50);
    });
    expect(screen.getByTestId('balance')).toHaveAnimatedProps({
      text: '₹2,000',
      defaultValue: '₹2,000',
    });
    jest.useRealTimers();
  });
});
