import { render, screen, fireEvent } from '@testing-library/react-native';
import { FinancialFocusScreen } from './FinancialFocusScreen';

describe('FinancialFocusScreen', () => {
  it('calls onContinue with the selected keys', () => {
    const onContinue = jest.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.press(screen.getByText(/Track my spending/));
    fireEvent.press(screen.getByText(/Reduce debt/));
    fireEvent.press(screen.getByText('Continue'));
    expect(onContinue).toHaveBeenCalledWith(['TRACK_SPENDING', 'REDUCE_DEBT']);
  });

  it('allows continuing with nothing selected', () => {
    const onContinue = jest.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.press(screen.getByText('Continue'));
    expect(onContinue).toHaveBeenCalledWith([]);
  });

  it('selecting "Just exploring" clears every other selection', () => {
    const onContinue = jest.fn();
    render(<FinancialFocusScreen onContinue={onContinue} />);
    fireEvent.press(screen.getByText(/Track my spending/));
    fireEvent.press(screen.getByText(/Just exploring/));
    fireEvent.press(screen.getByText('Continue'));
    expect(onContinue).toHaveBeenCalledWith(['EXPLORING']);
  });
});
