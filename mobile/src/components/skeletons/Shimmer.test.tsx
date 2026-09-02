import { AccessibilityInfo, Animated } from 'react-native';
import { act, render, screen } from '@testing-library/react-native';
import { Shimmer } from './Shimmer';
import { ThemeProvider } from '../../theme';

function renderShimmer(props: Partial<React.ComponentProps<typeof Shimmer>> = {}) {
  return render(
    <ThemeProvider>
      <Shimmer height={20} {...props} />
    </ThemeProvider>
  );
}

function flatStyle(style: unknown): Record<string, unknown> {
  return Array.isArray(style)
    ? Object.assign({}, ...(style as unknown[]).flat(Infinity).filter(Boolean))
    : (style as Record<string, unknown>);
}

// Shimmer deliberately hides itself from assistive technology (accessibilityElementsHidden +
// importantForAccessibility="no-hide-descendants") -- correct a11y behavior for a placeholder that
// carries no real information. RNTL excludes accessibility-hidden elements from every query by
// default (defaultIncludeHiddenElements: false), so every lookup here needs { hidden: true } to
// find it anyway. This is scoped per-query rather than a global test-setup config change, since
// flipping the default for the whole suite would silently affect every other test's hidden-element
// assumptions, not just this deliberately-hidden component's own tests.
describe('Shimmer', () => {
  it('renders a block sized to the given width and height', () => {
    renderShimmer({ width: 120, height: 20 });
    const block = screen.getByTestId('shimmer-block', { hidden: true });
    const style = flatStyle(block.props.style);
    expect(style.width).toBe(120);
    expect(style.height).toBe(20);
  });

  it('defaults to a full-width block with the theme border color', () => {
    renderShimmer();
    const style = flatStyle(screen.getByTestId('shimmer-block', { hidden: true }).props.style);
    expect(style.width).toBe('100%');
    // light.border from src/theme/palette.ts -- ThemeProvider defaults to 'system', which resolves
    // to light under the test runner's default (non-dark) color scheme.
    expect(style.backgroundColor).toBe('#E6EAF2');
  });

  it('is hidden from assistive technology, since it carries no information of its own', () => {
    renderShimmer();
    const block = screen.getByTestId('shimmer-block', { hidden: true });
    expect(block.props.accessibilityElementsHidden).toBe(true);
    expect(block.props.importantForAccessibility).toBe('no-hide-descendants');
  });

  it('accepts a custom testID so a composed skeleton can query it distinctly', () => {
    renderShimmer({ testID: 'skeleton-chart-bar' });
    expect(screen.getByTestId('skeleton-chart-bar', { hidden: true })).toBeTruthy();
    expect(screen.queryByTestId('shimmer-block', { hidden: true })).toBeNull();
  });

  // Every Shimmer block is individually hidden from assistive tech (see above), which otherwise
  // leaves no signal at all that the screen is loading -- the ActivityIndicator this system
  // replaced was at least a real element VoiceOver/TalkBack announced on its own.
  describe('loading announcement', () => {
    it('announces once when the first Shimmer of a loading episode mounts', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
      renderShimmer();
      expect(announce).toHaveBeenCalledWith('Loading');
      expect(announce).toHaveBeenCalledTimes(1);
    });

    it('does not announce again for additional Shimmers in the same loading episode', () => {
      const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
      render(
        <ThemeProvider>
          <Shimmer height={20} />
          <Shimmer height={20} />
          <Shimmer height={20} />
        </ThemeProvider>
      );
      expect(announce).toHaveBeenCalledTimes(1);
    });
  });
});

/**
 * The one animation in this app that has to ask about Reduce Motion itself. AnimatedNumber and
 * ChartReveal are Reanimated-based, and Reanimated defaults every animation to
 * `ReduceMotion.System`, so they already honour the setting for free. This loop is React Native's
 * own Animated API, which has no such behaviour -- and it is the worst offender of the three: an
 * indefinitely repeating pulse (exactly what the setting exists to suppress) that is on screen
 * during every loading state in the app, not for one 450ms transition.
 */
describe('Reduce Motion', () => {
  afterEach(() => jest.restoreAllMocks());

  it('does not start the pulse loop when the OS setting is on', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(true);
    const loop = jest.spyOn(Animated, 'loop');

    renderShimmer();
    // The check is a native round trip, so the decision lands a microtask later.
    await act(async () => {});

    expect(loop).not.toHaveBeenCalled();
  });

  it('still renders a visible placeholder rather than a frozen invisible one', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(true);

    renderShimmer();
    await act(async () => {});

    // Suppressing the motion must not suppress the block: it is still standing in for content.
    expect(screen.getByTestId('shimmer-block', { hidden: true })).toBeTruthy();
  });

  it('still announces loading, since that is not motion', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(true);
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');

    renderShimmer();
    await act(async () => {});

    expect(announce).toHaveBeenCalledWith('Loading');
  });

  it('animates as before when the setting is off', async () => {
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockResolvedValue(false);
    const loop = jest.spyOn(Animated, 'loop');

    renderShimmer();
    await act(async () => {});

    expect(loop).toHaveBeenCalled();
  });

  it('falls back to animating when the device cannot answer', async () => {
    // Failing toward the pre-existing behaviour beats a placeholder frozen at a fixed opacity,
    // which reads as a broken screen rather than a loading one.
    jest.spyOn(AccessibilityInfo, 'isReduceMotionEnabled').mockRejectedValue(new Error('nope'));
    const loop = jest.spyOn(Animated, 'loop');

    renderShimmer();
    await act(async () => {});

    expect(loop).toHaveBeenCalled();
  });
});
