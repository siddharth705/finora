import {
  CASHFLOW_PAD_TOP, CASHFLOW_PLOT_HEIGHT, DONUT_CENTER, DONUT_RADIUS, DONUT_SIZE, DONUT_STROKE,
  arcPath, buildArcs, cashFlowScale, pointOnCircle,
} from './chartGeometry';

describe('donut geometry', () => {
  it('starts the first slice at twelve o’clock', () => {
    const top = pointOnCircle(0);
    expect(top.x).toBeCloseTo(DONUT_CENTER);
    expect(top.y).toBeCloseTo(DONUT_CENTER - DONUT_RADIUS);
  });

  it('keeps every point inside the canvas', () => {
    for (let angle = 0; angle <= 360; angle += 7) {
      const p = pointOnCircle(angle);
      expect(p.x).toBeGreaterThanOrEqual(DONUT_STROKE / 2 - 1e-9);
      expect(p.x).toBeLessThanOrEqual(DONUT_SIZE - DONUT_STROKE / 2 + 1e-9);
      expect(p.y).toBeGreaterThanOrEqual(DONUT_STROKE / 2 - 1e-9);
      expect(p.y).toBeLessThanOrEqual(DONUT_SIZE - DONUT_STROKE / 2 + 1e-9);
    }
  });

  it('tiles slices around the circle with no gap or overlap', () => {
    const arcs = buildArcs([
      { label: 'a', value: 50 },
      { label: 'b', value: 30 },
      { label: 'c', value: 20 },
    ]);
    expect(arcs).toHaveLength(3);
    expect(arcs[0].end).toBeCloseTo(180); // 50% == half the circle
    expect(arcs[2].end).toBeCloseTo(360);
    expect(arcs[1].start).toBe(arcs[0].end);
    expect(arcs[2].start).toBe(arcs[1].end);
  });

  it('sets the large-arc flag only past a half circle', () => {
    expect(arcPath(0, 90)).toContain(' 0 1 ');
    expect(arcPath(0, 200)).toContain(' 1 1 ');
  });

  // A 360° arc's start and end points are identical, so the path draws nothing at all -- an
  // account with a single spend category would render an empty ring without this branch.
  it('flags a single 100% slice as a full circle', () => {
    const arcs = buildArcs([{ label: 'only', value: 42 }]);
    expect(arcs).toHaveLength(1);
    expect(arcs[0].full).toBe(true);
  });

  it('yields nothing for empty or all-zero input', () => {
    expect(buildArcs([])).toEqual([]);
    expect(buildArcs([{ label: 'z', value: 0 }])).toEqual([]);
  });

  it('drops zero-value categories but still closes the circle', () => {
    const arcs = buildArcs([
      { label: 'a', value: 10 },
      { label: 'zero', value: 0 },
      { label: 'b', value: 10 },
    ]);
    expect(arcs).toHaveLength(2);
    expect(arcs[1].end).toBeCloseTo(360);
  });
});

describe('cash flow scale', () => {
  const points = [
    { income: 50000, expense: 32000 },
    { income: 61000, expense: 47000 },
    { income: 45000, expense: 51000 },
  ];
  const WIDTH = 320;

  it('spans the full width and plot height', () => {
    const { xAt, yAt, max } = cashFlowScale(points, WIDTH);
    expect(xAt(0)).toBe(0);
    expect(xAt(points.length - 1)).toBeCloseTo(WIDTH);
    expect(yAt(max)).toBeCloseTo(CASHFLOW_PAD_TOP);
    expect(yAt(0)).toBeCloseTo(CASHFLOW_PAD_TOP + CASHFLOW_PLOT_HEIGHT);
  });

  it('shares one scale across both series so heights are comparable', () => {
    const { yAt } = cashFlowScale(points, WIDTH);
    // Larger value must sit higher on screen, i.e. a smaller y.
    expect(yAt(points[0].income)).toBeLessThan(yAt(points[0].expense));
    expect(yAt(points[2].income)).toBeGreaterThan(yAt(points[2].expense));
  });

  it('centres a single point instead of dividing by zero', () => {
    const { xAt, yAt } = cashFlowScale([{ income: 10, expense: 5 }], WIDTH);
    expect(xAt(0)).toBe(WIDTH / 2);
    expect(Number.isNaN(yAt(10))).toBe(false);
  });

  it('survives an all-zero month set', () => {
    const { yAt, max } = cashFlowScale(
      [
        { income: 0, expense: 0 },
        { income: 0, expense: 0 },
      ],
      WIDTH
    );
    expect(max).toBe(1); // floored, never 0
    expect(Number.isFinite(yAt(0))).toBe(true);
    expect(yAt(0)).toBeCloseTo(CASHFLOW_PAD_TOP + CASHFLOW_PLOT_HEIGHT);
  });
});
