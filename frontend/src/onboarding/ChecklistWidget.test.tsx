import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { describe, it, expect, vi } from 'vitest';
import { ChecklistWidget } from './ChecklistWidget';
import { onboardingApi } from '../api/endpoints';

vi.mock('../api/endpoints', () => ({ onboardingApi: { getChecklist: vi.fn() } }));

function renderWithClient(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>{ui}</QueryClientProvider>);
}

describe('ChecklistWidget', () => {
  it('shows progress text for a partially complete checklist', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [{ key: 'COMPLETE_PROFILE', completed: true }],
      completedCount: 4, totalCount: 6,
    });
    renderWithClient(<ChecklistWidget />);
    await waitFor(() => expect(screen.getByText('4 of 6 completed')).toBeInTheDocument());
  });

  it('renders nothing once completedCount equals totalCount', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [], completedCount: 6, totalCount: 6,
    });
    const { container } = renderWithClient(<ChecklistWidget />);
    await waitFor(() => expect(container.textContent).toBe(''));
  });

  it('shows every item label', async () => {
    vi.mocked(onboardingApi.getChecklist).mockResolvedValue({
      items: [
        { key: 'COMPLETE_PROFILE', completed: true },
        { key: 'IMPORT_STATEMENT', completed: false },
        { key: 'REVIEW_TRANSACTIONS', completed: false },
        { key: 'CREATE_BUDGET', completed: false },
        { key: 'CREATE_GOAL', completed: false },
        { key: 'VIEW_INSIGHTS', completed: false },
      ],
      completedCount: 1, totalCount: 6,
    });
    renderWithClient(<ChecklistWidget />);
    await waitFor(() => expect(screen.getByText(/Complete your profile/)).toBeInTheDocument());
    expect(screen.getByText(/Import first statement/)).toBeInTheDocument();
  });
});
