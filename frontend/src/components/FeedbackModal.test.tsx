import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { FeedbackModal } from './FeedbackModal';
import { feedbackApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  feedbackApi: { submit: vi.fn() },
}));

function renderModal(path = '/app') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <FeedbackModal onClose={vi.fn()} />
    </MemoryRouter>
  );
}

describe('FeedbackModal', () => {
  beforeEach(() => {
    vi.mocked(feedbackApi.submit).mockReset();
  });

  it('derives context from the current route rather than asking the user to pick one', async () => {
    const user = userEvent.setup();
    vi.mocked(feedbackApi.submit).mockResolvedValue({
      id: 'fb-1', userId: 'user-1', type: 'GENERAL', context: 'TRANSACTIONS', source: 'WEB',
      message: 'Great feature', createdAt: '2026-09-04T10:00:00Z',
    });
    renderModal('/app/transactions');

    await user.type(screen.getByLabelText(/your feedback/i), 'Great feature');
    await user.click(screen.getByRole('button', { name: /send feedback/i }));

    expect(feedbackApi.submit).toHaveBeenCalledWith({ type: 'GENERAL', context: 'TRANSACTIONS', message: 'Great feature' });
    expect(await screen.findByText(/thanks for the feedback/i)).toBeInTheDocument();
  });

  it('sends the selected type along with the trimmed message', async () => {
    const user = userEvent.setup();
    vi.mocked(feedbackApi.submit).mockResolvedValue({
      id: 'fb-1', userId: 'user-1', type: 'BUG', context: 'DASHBOARD', source: 'WEB',
      message: 'Chart is broken', createdAt: '2026-09-04T10:00:00Z',
    });
    renderModal('/app');

    await user.selectOptions(screen.getByLabelText(/what kind of feedback/i), 'BUG');
    await user.type(screen.getByLabelText(/your feedback/i), '  Chart is broken  ');
    await user.click(screen.getByRole('button', { name: /send feedback/i }));

    expect(feedbackApi.submit).toHaveBeenCalledWith({ type: 'BUG', context: 'DASHBOARD', message: 'Chart is broken' });
  });

  it('keeps Send disabled until a message is entered', () => {
    renderModal();
    expect(screen.getByRole('button', { name: /send feedback/i })).toBeDisabled();
  });

  it('shows the server error message on failure and does not show the success state', async () => {
    const user = userEvent.setup();
    vi.mocked(feedbackApi.submit).mockRejectedValue({ response: { data: { message: 'Could not save' } } });
    renderModal();

    await user.type(screen.getByLabelText(/your feedback/i), 'Something');
    await user.click(screen.getByRole('button', { name: /send feedback/i }));

    expect(await screen.findByText('Could not save')).toBeInTheDocument();
    expect(screen.queryByText(/thanks for the feedback/i)).not.toBeInTheDocument();
  });
});
