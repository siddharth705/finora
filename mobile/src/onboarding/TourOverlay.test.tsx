import { render, screen, fireEvent } from '@testing-library/react-native';
import { TourOverlay } from './TourOverlay';
import { TourTargetProvider } from './TourTargetRegistry';
import type { TourStep } from './tourSteps';

const STEPS: TourStep[] = [
  { key: 'a', tab: 'Home', title: 'Step A', body: 'Body A' },
  { key: 'b', tab: 'Home', title: 'Step B', body: 'Body B' },
];

describe('TourOverlay', () => {
  it('shows the first step and advances on Next', () => {
    const navigateMock = jest.fn();
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={navigateMock} onFinish={jest.fn()} onSkip={jest.fn()} />
      </TourTargetProvider>
    );
    expect(screen.getByText('Step A')).toBeTruthy();
    fireEvent.press(screen.getByText('Next'));
    expect(screen.getByText('Step B')).toBeTruthy();
  });

  it("calls navigateToTab with the step's tab whenever the step changes", () => {
    const navigateMock = jest.fn();
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={navigateMock} onFinish={jest.fn()} onSkip={jest.fn()} />
      </TourTargetProvider>
    );
    expect(navigateMock).toHaveBeenCalledWith('Home');
  });

  it('calls onFinish after Next on the last step', () => {
    const onFinish = jest.fn();
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={jest.fn()} onFinish={onFinish} onSkip={jest.fn()} />
      </TourTargetProvider>
    );
    fireEvent.press(screen.getByText('Next'));
    fireEvent.press(screen.getByText('Finish'));
    expect(onFinish).toHaveBeenCalled();
  });

  it('calls onSkip from Skip', () => {
    const onSkip = jest.fn();
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={jest.fn()} onFinish={jest.fn()} onSkip={onSkip} />
      </TourTargetProvider>
    );
    fireEvent.press(screen.getByText('Skip'));
    expect(onSkip).toHaveBeenCalled();
  });

  it('goes back to the previous step on Back', () => {
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={jest.fn()} onFinish={jest.fn()} onSkip={jest.fn()} />
      </TourTargetProvider>
    );
    fireEvent.press(screen.getByText('Next'));
    fireEvent.press(screen.getByText('Back'));
    expect(screen.getByText('Step A')).toBeTruthy();
  });

  it('does not show a Back button on the first step', () => {
    render(
      <TourTargetProvider>
        <TourOverlay steps={STEPS} navigateToTab={jest.fn()} onFinish={jest.fn()} onSkip={jest.fn()} />
      </TourTargetProvider>
    );
    expect(screen.queryByText('Back')).toBeNull();
  });
});
