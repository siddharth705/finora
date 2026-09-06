import { render, screen, fireEvent } from '@testing-library/react-native';
import { WelcomeScreen } from './WelcomeScreen';

describe('WelcomeScreen', () => {
  it('calls onStart when Start Setup is pressed', () => {
    const onStart = jest.fn();
    render(<WelcomeScreen onStart={onStart} onSkip={jest.fn()} />);
    fireEvent.press(screen.getByText('Start Setup'));
    expect(onStart).toHaveBeenCalled();
  });

  it('calls onSkip when Skip for Now is pressed', () => {
    const onSkip = jest.fn();
    render(<WelcomeScreen onStart={jest.fn()} onSkip={onSkip} />);
    fireEvent.press(screen.getByText('Skip for Now'));
    expect(onSkip).toHaveBeenCalled();
  });
});
