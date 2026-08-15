import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import GmailReview from './GmailReview';
import { gmailApi, categoriesApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({
  gmailApi: { reviewQueue: vi.fn(), approve: vi.fn(), reject: vi.fn() },
  categoriesApi: { list: vi.fn() },
}));

function item(overrides: Partial<Record<string, unknown>> = {}) {
  return {
    sessionId: 'session-1',
    merchant: 'Amazon',
    merchantDomain: 'amazon.in',
    amount: 1299,
    date: '2026-08-15',
    category: 'Other',
    confidence: 0.9,
    stagedAt: '2026-08-15T05:00:00Z',
    ...overrides,
  };
}

function renderPage() {
  return render(
    <MemoryRouter>
      <GmailReview />
    </MemoryRouter>
  );
}

describe('GmailReview', () => {
  beforeEach(() => {
    vi.mocked(gmailApi.reviewQueue).mockReset().mockResolvedValue([item()]);
    vi.mocked(gmailApi.approve).mockReset().mockResolvedValue(undefined as any);
    vi.mocked(gmailApi.reject).mockReset().mockResolvedValue(undefined as any);
    vi.mocked(categoriesApi.list).mockReset().mockResolvedValue([
      { name: 'Shopping' }, { name: 'Transport' }, { name: 'Other' },
    ] as any);
  });

  it('renders a pending receipt with amount, date and confidence', async () => {
    renderPage();

    expect(await screen.findByText('Amazon')).toBeInTheDocument();
    expect(screen.getByText('₹1,299.00')).toBeInTheDocument();
    expect(screen.getByText(/90% confidence/)).toBeInTheDocument();
  });

  it('shows an empty state when nothing is pending', async () => {
    vi.mocked(gmailApi.reviewQueue).mockResolvedValue([]);
    renderPage();

    expect(await screen.findByText(/nothing waiting for review/i)).toBeInTheDocument();
  });

  it('approves with no category override when the category was left untouched', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /approve/i }));

    await waitFor(() => expect(gmailApi.approve).toHaveBeenCalledWith('session-1', undefined));
  });

  it('approves with the edited category when it was changed', async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findByText('Amazon');
    await user.selectOptions(screen.getByLabelText(/category/i), 'Shopping');
    await user.click(screen.getByRole('button', { name: /approve/i }));

    await waitFor(() => expect(gmailApi.approve).toHaveBeenCalledWith('session-1', 'Shopping'));
  });

  it('removes the row from the list once approved', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /approve/i }));

    await waitFor(() => expect(screen.queryByText('Amazon')).not.toBeInTheDocument());
  });

  it('rejects a receipt and removes it from the list', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(await screen.findByRole('button', { name: /reject/i }));

    await waitFor(() => expect(gmailApi.reject).toHaveBeenCalledWith('session-1'));
    await waitFor(() => expect(screen.queryByText('Amazon')).not.toBeInTheDocument());
  });

  it('shows a row-level error and keeps the item when approval fails', async () => {
    const user = userEvent.setup();
    vi.mocked(gmailApi.approve).mockRejectedValue(new Error('network error'));
    renderPage();

    await user.click(await screen.findByRole('button', { name: /approve/i }));

    expect(await screen.findByText(/couldn't approve/i)).toBeInTheDocument();
    expect(screen.getByText('Amazon')).toBeInTheDocument();
  });
});
