import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Pagination } from './Pagination';

describe('Pagination', () => {
  it('renders nothing when there are no elements to page through', () => {
    const { container } = render(
      <Pagination page={0} totalPages={0} totalElements={0} pageSize={20} onPageChange={() => {}} />
    );
    expect(container).toBeEmptyDOMElement();
  });

  it('shows the correct "Showing X-Y of Z" range for a full middle page', () => {
    render(<Pagination page={1} totalPages={5} totalElements={100} pageSize={20} onPageChange={() => {}} />);
    // page 1 (zero-indexed) of size 20 covers items 21-40.
    expect(screen.getByText('Showing 21–40 of 100')).toBeInTheDocument();
    expect(screen.getByText('Page 2 of 5')).toBeInTheDocument();
  });

  it('clamps the upper end of the range on a partial last page', () => {
    render(<Pagination page={4} totalPages={5} totalElements={85} pageSize={20} onPageChange={() => {}} />);
    // page 4 (zero-indexed) of size 20 would nominally end at 100, clamped to 85.
    expect(screen.getByText('Showing 81–85 of 85')).toBeInTheDocument();
  });

  it('disables Previous on the first page', () => {
    render(<Pagination page={0} totalPages={3} totalElements={50} pageSize={20} onPageChange={() => {}} />);
    const [prevButton] = screen.getAllByRole('button');
    expect(prevButton).toBeDisabled();
  });

  it('clicking Previous on a middle page calls onPageChange(page - 1)', async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    render(<Pagination page={1} totalPages={3} totalElements={50} pageSize={20} onPageChange={onPageChange} />);

    const [prevButton] = screen.getAllByRole('button');
    expect(prevButton).not.toBeDisabled();
    await user.click(prevButton);
    expect(onPageChange).toHaveBeenCalledWith(0);
  });

  it('disables Next on the last page', () => {
    render(<Pagination page={2} totalPages={3} totalElements={50} pageSize={20} onPageChange={() => {}} />);
    const [, nextButton] = screen.getAllByRole('button');
    expect(nextButton).toBeDisabled();
  });

  it('clicking Next on a middle page calls onPageChange(page + 1)', async () => {
    const user = userEvent.setup();
    const onPageChange = vi.fn();
    render(<Pagination page={1} totalPages={3} totalElements={50} pageSize={20} onPageChange={onPageChange} />);

    const [, nextButton] = screen.getAllByRole('button');
    expect(nextButton).not.toBeDisabled();
    await user.click(nextButton);
    expect(onPageChange).toHaveBeenCalledWith(2);
  });
});
