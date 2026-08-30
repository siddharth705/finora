import { fireEvent, render, screen } from '@testing-library/react-native';
import { OptionPickerModal } from './OptionPickerModal';
import { hapticSelection } from '../lib/haptics';

jest.mock('../lib/haptics', () => ({ hapticSelection: jest.fn() }));

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
});
