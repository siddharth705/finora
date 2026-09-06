import { render, screen, waitFor } from '@testing-library/react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ChecklistWidget } from './ChecklistWidget';
import { onboardingApi } from '../api/endpoints';

jest.mock('../api/endpoints', () => ({ onboardingApi: { getChecklist: jest.fn() } }));

function renderWithClient() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={client}>
      <ChecklistWidget />
    </QueryClientProvider>
  );
}

describe('ChecklistWidget', () => {
  it('shows progress text for a partially complete checklist', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [{ key: 'COMPLETE_PROFILE', completed: true }],
      completedCount: 4, totalCount: 6,
    });
    renderWithClient();
    await waitFor(() => expect(screen.getByText('4 of 6 completed')).toBeTruthy());
  });

  it('renders nothing once completedCount equals totalCount', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
      items: [], completedCount: 6, totalCount: 6,
    });
    renderWithClient();
    await waitFor(() => expect(screen.queryByText(/Getting Started/)).toBeNull());
  });

  it('shows every item label', async () => {
    (onboardingApi.getChecklist as jest.Mock).mockResolvedValue({
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
    renderWithClient();
    await waitFor(() => expect(screen.getByText(/Complete your profile/)).toBeTruthy());
    expect(screen.getByText(/Import first statement/)).toBeTruthy();
  });
});
