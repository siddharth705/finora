import { render } from '@testing-library/react';
import { createRef } from 'react';
import { describe, expect, it } from 'vitest';
import { DocumentStack } from './DocumentStack';

describe('DocumentStack', () => {
  it('forwards its ref to the root element', () => {
    const ref = createRef<HTMLDivElement>();
    const { container } = render(<DocumentStack ref={ref} />);
    expect(ref.current).toBe(container.firstElementChild);
  });

  it('marks its animatable elements with the data-target attributes the timeline queries', () => {
    const ref = createRef<HTMLDivElement>();
    render(<DocumentStack ref={ref} />);
    expect(ref.current?.querySelector('[data-target="doc-pdf"]')).toBeInTheDocument();
    expect(ref.current?.querySelector('[data-target="doc-csv"]')).toBeInTheDocument();
    expect(ref.current?.querySelectorAll('[data-target^="chip-"]').length).toBeGreaterThanOrEqual(3);
  });
});
