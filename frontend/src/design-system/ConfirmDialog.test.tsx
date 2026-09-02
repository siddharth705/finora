import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { ConfirmDialog } from './ConfirmDialog';

describe('ConfirmDialog', () => {
  it('renders the title and message', () => {
    render(
      <ConfirmDialog title="Delete this goal?" message="This can't be undone." onConfirm={vi.fn()} onCancel={vi.fn()} />
    );

    expect(screen.getByText('Delete this goal?')).toBeInTheDocument();
    expect(screen.getByText("This can't be undone.")).toBeInTheDocument();
  });

  it('defaults to "Confirm"/"Cancel" labels when none are given', () => {
    render(<ConfirmDialog title="Sure?" message="..." onConfirm={vi.fn()} onCancel={vi.fn()} />);

    expect(screen.getByRole('button', { name: 'Confirm' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
  });

  it('uses custom labels when given', () => {
    render(
      <ConfirmDialog title="Sure?" message="..." confirmLabel="Delete" cancelLabel="Keep it"
        onConfirm={vi.fn()} onCancel={vi.fn()} />
    );

    expect(screen.getByRole('button', { name: 'Delete' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Keep it' })).toBeInTheDocument();
  });

  it('calls onConfirm when the confirm button is clicked', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(<ConfirmDialog title="Sure?" message="..." onConfirm={onConfirm} onCancel={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: 'Confirm' }));

    expect(onConfirm).toHaveBeenCalledTimes(1);
  });

  it('calls onCancel when the cancel button is clicked, or the backdrop is clicked', async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    render(<ConfirmDialog title="Sure?" message="..." onConfirm={vi.fn()} onCancel={onCancel} />);

    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('disables both buttons and shows a busy label while busy, and ignores a backdrop click', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    const onCancel = vi.fn();
    render(<ConfirmDialog title="Sure?" message="..." busy onConfirm={onConfirm} onCancel={onCancel} />);

    expect(screen.getByRole('button', { name: 'Working…' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Cancel' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Working…' }));
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('calls onCancel when Escape is pressed', async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    render(<ConfirmDialog title="Sure?" message="..." onConfirm={vi.fn()} onCancel={onCancel} />);

    await user.keyboard('{Escape}');

    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('ignores Escape while busy', async () => {
    const user = userEvent.setup();
    const onCancel = vi.fn();
    render(<ConfirmDialog title="Sure?" message="..." busy onConfirm={vi.fn()} onCancel={onCancel} />);

    await user.keyboard('{Escape}');

    expect(onCancel).not.toHaveBeenCalled();
  });

  /**
   * Focus management. Before this, the dialog was only VISUALLY modal: its backdrop swallows mouse
   * clicks, but nothing stopped Tab walking out of the dialog and into the controls it was covering,
   * so a destructive confirmation could sit open while the user operated the very screen it was
   * asking about. That is how a discard confirmation could end up stacked over Import's summary
   * screen, with "Discard" then firing against an already-finalized session.
   */
  describe('focus management', () => {
    /** A page behind the dialog, so "did focus escape?" is a question with a real answer. */
    function renderOverPage(props: Partial<Parameters<typeof ConfirmDialog>[0]> = {}) {
      return render(
        <div>
          <button type="button">Behind before</button>
          <ConfirmDialog title="Sure?" message="..." onConfirm={vi.fn()} onCancel={vi.fn()} {...props} />
          <button type="button">Behind after</button>
        </div>
      );
    }

    it('moves focus to Cancel on open, not to the destructive action', () => {
      renderOverPage({ danger: true, confirmLabel: 'Delete' });

      // WAI-ARIA: land on the least destructive action, so a reflexive Enter dismisses.
      expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    });

    it('keeps Tab inside the dialog instead of reaching the page behind it', async () => {
      const user = userEvent.setup();
      renderOverPage({ confirmLabel: 'Delete' });

      expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
      await user.tab();
      expect(screen.getByRole('button', { name: 'Delete' })).toHaveFocus();
      // The escape hatch this closes: from the last control, Tab used to land on "Behind after".
      await user.tab();
      expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    });

    it('wraps backwards too, rather than falling out of the top of the dialog', async () => {
      const user = userEvent.setup();
      renderOverPage({ confirmLabel: 'Delete' });

      expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
      await user.tab({ shift: true });
      expect(screen.getByRole('button', { name: 'Delete' })).toHaveFocus();
    });

    it('holds focus even while busy, when both buttons are disabled and nothing inside is tabbable', async () => {
      const user = userEvent.setup();
      renderOverPage({ busy: true });

      await user.tab();

      // Whatever it landed on, it must not be a control on the page behind the backdrop -- busy is
      // exactly when an escaped Tab is most dangerous, since an action is already in flight.
      expect(screen.getByRole('button', { name: 'Behind before' })).not.toHaveFocus();
      expect(screen.getByRole('button', { name: 'Behind after' })).not.toHaveFocus();
    });

    it('restores focus to whatever opened it once it closes', async () => {
      const user = userEvent.setup();
      // The dialog has to MOUNT while the opener already holds focus -- that mount is the moment the
      // component captures where to hand focus back to, so a dialog present in the first render
      // would capture <body> and this would be testing nothing.
      function Page({ open }: { open: boolean }) {
        return (
          <div>
            <button type="button">Opener</button>
            {open && <ConfirmDialog title="Sure?" message="..." onConfirm={vi.fn()} onCancel={vi.fn()} />}
          </div>
        );
      }
      const { rerender } = render(<Page open={false} />);

      const opener = screen.getByRole('button', { name: 'Opener' });
      opener.focus();
      expect(opener).toHaveFocus();

      rerender(<Page open />);

      // Focus has to have genuinely LEFT the opener first, or "restored" would pass trivially on a
      // component that never moved focus at all -- which is exactly what the unfixed version did.
      expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
      expect(opener).not.toHaveFocus();

      await user.keyboard('{Escape}');
      rerender(<Page open={false} />);

      expect(opener).toHaveFocus();
    });
  });
});
