import { render, screen } from '@testing-library/react-native';
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
});
