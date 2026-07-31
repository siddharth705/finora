import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { EntityDrawer, type EntityDrawerTab } from './EntityDrawer';

const TABS: EntityDrawerTab[] = [
  { id: 'summary', label: 'Summary', content: <p>Summary content</p> },
  { id: 'metadata', label: 'Metadata', content: <p>Metadata content</p> },
  { id: 'audit', label: 'Audit', content: <p>Audit content</p> },
];

describe('EntityDrawer', () => {
  it('renders nothing when closed', () => {
    const { container } = render(
      <EntityDrawer open={false} onClose={vi.fn()} title="Test Entity" tabs={TABS} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the title, subtitle, and the first tab by default when open', () => {
    render(
      <EntityDrawer open onClose={vi.fn()} title="HDFC Bank" subtitle="HDFC" tabs={TABS} />
    );

    expect(screen.getByText('HDFC Bank')).toBeInTheDocument();
    expect(screen.getByText('HDFC')).toBeInTheDocument();
    expect(screen.getByText('Summary content')).toBeInTheDocument();
    expect(screen.queryByText('Metadata content')).not.toBeInTheDocument();
  });

  it('switches tabs on click', async () => {
    const user = userEvent.setup();
    render(<EntityDrawer open onClose={vi.fn()} title="Test Entity" tabs={TABS} />);

    await user.click(screen.getByRole('button', { name: 'Metadata' }));

    expect(screen.getByText('Metadata content')).toBeInTheDocument();
    expect(screen.queryByText('Summary content')).not.toBeInTheDocument();
  });

  it('calls onClose when the close button is clicked', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<EntityDrawer open onClose={onClose} title="Test Entity" tabs={TABS} />);

    await user.click(screen.getByLabelText('Close'));

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('calls onClose when the backdrop is clicked', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<EntityDrawer open onClose={onClose} title="Test Entity" tabs={TABS} />);

    // The backdrop is the sibling absolute-positioned div behind the panel -- role="dialog" is
    // the outer flex container, its first child is the backdrop.
    const dialog = screen.getByRole('dialog');
    await user.click(dialog.firstElementChild as HTMLElement);

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('calls onClose when Escape is pressed', async () => {
    const onClose = vi.fn();
    const user = userEvent.setup();
    render(<EntityDrawer open onClose={onClose} title="Test Entity" tabs={TABS} />);

    await user.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('renders headerActions next to the close button', () => {
    render(
      <EntityDrawer
        open
        onClose={vi.fn()}
        title="Test Entity"
        tabs={TABS}
        headerActions={<button type="button">Custom Action</button>}
      />
    );

    expect(screen.getByRole('button', { name: 'Custom Action' })).toBeInTheDocument();
  });

  it('hides the tab bar entirely for a single-tab drawer', () => {
    render(
      <EntityDrawer
        open
        onClose={vi.fn()}
        title="Test Entity"
        tabs={[{ id: 'only', label: 'Only Tab', content: <p>Only content</p> }]}
      />
    );

    expect(screen.queryByRole('button', { name: 'Only Tab' })).not.toBeInTheDocument();
    expect(screen.getByText('Only content')).toBeInTheDocument();
  });

  it('resets to the first tab when a different entity is opened while already open', async () => {
    const user = userEvent.setup();
    const { rerender } = render(
      <EntityDrawer open onClose={vi.fn()} title="Entity A" tabs={TABS} />
    );
    await user.click(screen.getByRole('button', { name: 'Metadata' }));
    expect(screen.getByText('Metadata content')).toBeInTheDocument();

    rerender(<EntityDrawer open onClose={vi.fn()} title="Entity B" tabs={TABS} />);

    expect(screen.getByText('Summary content')).toBeInTheDocument();
  });
});
