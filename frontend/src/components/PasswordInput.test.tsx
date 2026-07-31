import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PasswordInput } from './PasswordInput';

describe('PasswordInput', () => {
  it('masks the value by default', () => {
    render(<PasswordInput value="secret123" onChange={() => {}} />);
    expect(screen.getByDisplayValue('secret123')).toHaveAttribute('type', 'password');
  });

  it('reveals the value when the show/hide toggle is clicked, and hides it again on a second click', async () => {
    const user = userEvent.setup();
    render(<PasswordInput value="secret123" onChange={() => {}} />);

    const input = screen.getByDisplayValue('secret123');
    expect(input).toHaveAttribute('type', 'password');

    const toggle = screen.getByRole('button', { name: 'Show password' });
    await user.click(toggle);

    expect(input).toHaveAttribute('type', 'text');
    expect(screen.getByRole('button', { name: 'Hide password' })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Hide password' }));
    expect(input).toHaveAttribute('type', 'password');
  });

  it('calls onChange with the new value as the user types', async () => {
    const user = userEvent.setup();
    const onChange = vi.fn();
    render(<PasswordInput value="" onChange={onChange} />);

    await user.type(screen.getByDisplayValue(''), 'a');

    expect(onChange).toHaveBeenCalledWith('a');
  });
});
