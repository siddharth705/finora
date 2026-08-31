import {
  CASHFLOW_PAD_TOP, CASHFLOW_PLOT_HEIGHT, DONUT_CENTER, DONUT_CIRCUMFERENCE, DONUT_RADIUS, DONUT_SIZE, DONUT_STROKE,
  TREND_PAD_TOP, TREND_PLOT_HEIGHT,
  arcLength, arcPath, bucketTopSlices, buildArcs, cashFlowScale, pointOnCircle, polylineLength, toSvgPoints,
  trendScale,
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

  /**
   * Slices are identified by position, not by label. Spend categories are unique, but investment
   * holdings are named by the user and two can genuinely share a name -- and DonutChart uses this
   * for both the React key and the colour lookup, so a shared label collapsed them into one key
   * and painted the second with the first's colour.
   */
  it('keeps same-named slices distinguishable by their original position', () => {
    const arcs = buildArcs([
      { label: 'Gold ETF', value: 10 },
      { label: 'Gold ETF', value: 30 },
    ]);
    expect(arcs.map((a) => a.index)).toEqual([0, 1]);
  });

  it('indexes against the caller’s array, not the filtered one', () => {
    const arcs = buildArcs([
      { label: 'a', value: 10 },
      { label: 'dropped', value: 0 },
      { label: 'b', value: 10 },
    ]);
    // 'b' is arc 1 but slice 2 -- colouring by arc position would read the dropped slice's colour.
    expect(arcs.map((a) => a.index)).toEqual([0, 2]);
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

describe('bucketTopSlices', () => {
  const colors = ['red', 'green', 'blue'];

  it('returns one slice per entry, sorted by value descending, when there is room in the palette', () => {
    const slices = bucketTopSlices([['b', 20], ['a', 30]], colors, 'Other');
    expect(slices).toEqual([
      { label: 'a', value: 30, color: 'red' },
      { label: 'b', value: 20, color: 'green' },
    ]);
  });

  it('folds everything past the palette size into a synthetic "Other" bucket', () => {
    const slices = bucketTopSlices(
      [['a', 40], ['b', 30], ['c', 20], ['d', 10]],
      colors,
      'Other'
    );
    expect(slices).toEqual([
      { label: 'a', value: 40, color: 'red' },
      { label: 'b', value: 30, color: 'green' },
      { label: 'Other', value: 30, color: 'blue' }, // c (20) + d (10)
    ]);
  });

  // The overflow bucket has to absorb a real entry of the same name rather than sit beside it --
  // "Other" is a category/holding name real data can genuinely have, and two identically-labelled
  // rows with different amounts is not something a reader can resolve.
  it('merges the overflow into an existing entry literally named "Other" instead of creating a second row', () => {
    const slices = bucketTopSlices(
      [['a', 40], ['b', 30], ['Other', 5], ['d', 10]],
      colors,
      'Other'
    );
    expect(slices).toEqual([
      { label: 'a', value: 40, color: 'red' },
      { label: 'b', value: 30, color: 'green' },
      { label: 'Other', value: 15, color: 'blue' }, // 5 (its own) + 10 (d, folded in)
    ]);
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

describe('net worth trend scale', () => {
  const WIDTH = 320;

  it('spans the full width and plot height across the data range', () => {
    const { xAt, yAt, min, max } = trendScale([120000, 180000, 150000], WIDTH);
    expect(xAt(0)).toBe(0);
    expect(xAt(2)).toBeCloseTo(WIDTH);
    expect(min).toBe(120000);
    expect(max).toBe(180000);
    expect(yAt(max)).toBeCloseTo(TREND_PAD_TOP);
    expect(yAt(min)).toBeCloseTo(TREND_PAD_TOP + TREND_PLOT_HEIGHT);
  });

  /**
   * The reason this scale exists rather than reusing cashFlowScale: net worth is a signed
   * quantity. Anchoring at zero the way income/expense does would push a negative series off the
   * bottom of the plot entirely.
   */
  it('plots a negative net worth inside the canvas', () => {
    const { yAt } = trendScale([-50000, -20000], WIDTH);
    expect(yAt(-50000)).toBeCloseTo(TREND_PAD_TOP + TREND_PLOT_HEIGHT);
    expect(yAt(-20000)).toBeCloseTo(TREND_PAD_TOP);
  });

  it('centres a flat series instead of dividing by zero', () => {
    const { yAt } = trendScale([90000, 90000, 90000], WIDTH);
    expect(Number.isNaN(yAt(90000))).toBe(false);
    expect(yAt(90000)).toBeCloseTo(TREND_PAD_TOP + TREND_PLOT_HEIGHT / 2);
  });

  it('centres a single point instead of dividing by zero', () => {
    const { xAt, yAt } = trendScale([42], WIDTH);
    expect(xAt(0)).toBe(WIDTH / 2);
    expect(Number.isFinite(yAt(42))).toBe(true);
  });

  it('survives an empty series', () => {
    const { xAt, yAt } = trendScale([], WIDTH);
    expect(Number.isFinite(xAt(0))).toBe(true);
    expect(Number.isFinite(yAt(0))).toBe(true);
  });
});

describe('chart reveal geometry', () => {
  it('measures a quarter-circle arc as a quarter of the circumference', () => {
    expect(arcLength(90)).toBeCloseTo((Math.PI * DONUT_RADIUS) / 2);
  });

  it('measures a full sweep as the exact circumference', () => {
    expect(arcLength(360)).toBeCloseTo(2 * Math.PI * DONUT_RADIUS);
    expect(DONUT_CIRCUMFERENCE).toBeCloseTo(2 * Math.PI * DONUT_RADIUS);
  });

  it('measures a right-triangle polyline by straight-line distance, not by bounding box', () => {
    // (0,0) -> (3,0) -> (3,4): legs of 3 and 4, so 3 + 4 = 7 -- not the diagonal (5) and not the
    // sum of both axes' extents guessed independently.
    const length = polylineLength([{ x: 0, y: 0 }, { x: 3, y: 0 }, { x: 3, y: 4 }]);
    expect(length).toBeCloseTo(7);
  });

  it('is zero for a single point or an empty series -- nothing to draw, nothing to animate', () => {
    expect(polylineLength([])).toBe(0);
    expect(polylineLength([{ x: 10, y: 10 }])).toBe(0);
  });

  it('renders points as the SVG polyline attribute string, from the same array polylineLength consumes', () => {
    expect(toSvgPoints([{ x: 0, y: 0 }, { x: 3, y: 0 }, { x: 3, y: 4 }])).toBe('0,0 3,0 3,4');
  });

  it('is an empty string for an empty series', () => {
    expect(toSvgPoints([])).toBe('');
  });
});
