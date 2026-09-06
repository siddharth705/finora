import { fireEvent, render, screen } from '@testing-library/react-native';
import { OptionPickerModal } from './OptionPickerModal';
import { hapticSelection } from '../lib/haptics';

jest.mock('../lib/haptics');

describe('OptionPickerModal', () => {
  it('fires a selection haptic when an option is tapped', () => {
    const onSelect = jest.fn();
    render(
      <OptionPickerModal
        visible
        title="Category"
        options={['Groceries', 'Dining']}
        selected={null}
        onSelect={onSelect}
        onClose={jest.fn()}
      />
    );

    fireEvent.press(screen.getByRole('button', { name: 'Groceries' }));

    expect(hapticSelection).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith('Groceries');
  });

  /**
   * The sheet is the whole correction affordance on mobile, so a screen reader has to land inside
   * it and be able to tell what is currently selected. Before this, focus landed on the backdrop.
   */
  describe('accessibility', () => {
    function renderSheet(selected: string | null = null) {
      return render(
        <OptionPickerModal
          visible
          title="Change category"
          options={['Groceries', 'Dining']}
          selected={selected}
          onSelect={jest.fn()}
          onClose={jest.fn()}
        />
      );
    }

    it('keeps the dismiss backdrop out of the accessibility tree', () => {
      // As the Modal's first child it took initial VoiceOver focus, so the sheet announced
      // "Close ... picker" instead of itself -- and one double-tap at that landing spot dismissed
      // the sheet the user had just opened. "Done" and Android back remain the accessible exits.
      renderSheet();
      expect(screen.queryByLabelText(/Close .* picker/i)).toBeNull();
    });

    it('exposes the sheet title as a heading', () => {
      renderSheet();
      expect(screen.getByRole('header', { name: 'Change category' })).toBeTruthy();
    });

    it('conveys the current choice by state, not by reading out the tick', () => {
      renderSheet('Groceries');
      expect(screen.getByRole('button', { name: 'Groceries' }).props.accessibilityState)
        .toMatchObject({ selected: true });
      // Absence IS the assertion: RNTL's queries skip accessibility-hidden elements by default,
      // so a tick that a screen reader would announce is findable here and a correctly hidden one
      // is not. (Asserting `.not.toHaveTextContent('✓')` on the row instead passed against the
      // unfixed component -- that matcher never saw the glyph either way -- and pinned nothing.)
      // The glyph is decorative: announcing "check mark" on top of the selected state it
      // duplicates is pure noise.
      expect(screen.queryByText('✓')).toBeNull();
    });
  });
});
