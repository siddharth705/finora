import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import Diagnostics from './Diagnostics';
import { useAdminAuth } from '../context/AdminAuthContext';
import { mockAdminAuthState } from '../test/mockAdminAuth';
import { adminDiagnosticsApi } from '../api/endpoints';
import type { PlatformDiagnosticsDto } from '../types';

vi.mock('../context/AdminAuthContext', () => ({
  useAdminAuth: vi.fn(),
}));
vi.mock('../api/endpoints', () => ({
  adminDiagnosticsApi: { overview: vi.fn() },
}));

const notifySuccess = vi.fn();
const notifyError = vi.fn();
vi.mock('../context/NotificationContext', () => ({
  useNotify: () => ({ success: notifySuccess, error: notifyError }),
}));

function renderPage() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <Diagnostics />
      </MemoryRouter>
    </QueryClientProvider>
  );
}

function mockAuth(permissions: string[]) {
  vi.mocked(useAdminAuth).mockReturnValue(mockAdminAuthState({
    hasPermission: (p: string) => permissions.includes(p),
    permissions,
    fullName: 'Ops Admin',
    logout: vi.fn(),
  }));
}

// jsdom's navigator.clipboard is a getter-only property (no setter) -- Object.assign/direct
// assignment throws "Cannot set property clipboard of #<Navigator> which has only a getter".
// defineProperty with configurable:true is what actually lets each test install its own mock.
function mockClipboard(writeText: (text: string) => Promise<void>) {
  Object.defineProperty(navigator, 'clipboard', {
    value: { writeText },
    configurable: true,
  });
}

const DIAGNOSTICS: PlatformDiagnosticsDto = {
  application: { version: '1.2.3', gitCommit: 'abc1234', springProfile: 'prod' },
  runtime: { uptimeSeconds: 3600, flywayVersion: 'V39', cacheEnabled: true },
  health: { overallStatus: 'UP', providers: [] },
  configuration: { registrationsEnabled: true, setupCompleted: true, phoneVerificationPolicy: 'REQUIRED' },
  recentImports: [],
};

/**
 * Bug fix: CopyDiagnosticsButton's handleCopy() used to await
 * navigator.clipboard.writeText() with no try/catch at all -- a rejection (an unfocused tab, a
 * denied permission, a non-HTTPS deployment where the Clipboard API requires a secure context)
 * produced an unhandled promise rejection: the button silently did nothing, no "Copied" state and
 * no error shown to the admin who clicked it.
 */
describe('Diagnostics copy button', () => {
  beforeEach(() => {
    vi.mocked(adminDiagnosticsApi.overview).mockReset();
    notifySuccess.mockReset();
    notifyError.mockReset();
  });

  it('shows "Copied" after a successful clipboard write', async () => {
    const user = userEvent.setup();
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
    vi.mocked(adminDiagnosticsApi.overview).mockResolvedValue(DIAGNOSTICS);
    mockClipboard(vi.fn().mockResolvedValue(undefined));

    renderPage();

    const button = await screen.findByRole('button', { name: 'Copy diagnostics' });
    await user.click(button);

    await waitFor(() => expect(screen.getByText('Copied')).toBeInTheDocument());
    expect(notifyError).not.toHaveBeenCalled();
  });

  it('shows an error notification instead of failing silently when the clipboard write rejects', async () => {
    const user = userEvent.setup();
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
    vi.mocked(adminDiagnosticsApi.overview).mockResolvedValue(DIAGNOSTICS);
    mockClipboard(vi.fn().mockRejectedValue(new DOMException('Document is not focused', 'NotAllowedError')));

    renderPage();

    const button = await screen.findByRole('button', { name: 'Copy diagnostics' });
    await user.click(button);

    await waitFor(() => expect(notifyError).toHaveBeenCalledWith(
      'Could not copy to clipboard -- your browser may have blocked it.'
    ));
    // Must NOT flip to the "Copied" state when the write actually failed.
    expect(screen.queryByText('Copied')).not.toBeInTheDocument();
  });
});

describe('Diagnostics', () => {
  beforeEach(() => {
    vi.mocked(adminDiagnosticsApi.overview).mockReset();
  });

  it('shows an access-denied message when the account lacks PLATFORM_DIAGNOSTICS_VIEW', () => {
    mockAuth([]);
    vi.mocked(adminDiagnosticsApi.overview).mockResolvedValue(DIAGNOSTICS);

    renderPage();

    expect(screen.getByText("You don't have access to this section")).toBeInTheDocument();
  });

  /**
   * Bug fix: this page used to render `if (!data) return null` on a failed diagnostics fetch --
   * completely blank content, with zero indication anything went wrong, on a page whose entire
   * purpose is telling an admin/developer the platform's own state.
   */
  it('shows an error message instead of a blank page when the diagnostics fetch fails', async () => {
    mockAuth(['PLATFORM_DIAGNOSTICS_VIEW']);
    vi.mocked(adminDiagnosticsApi.overview).mockRejectedValue(new Error('network error'));

    renderPage();

    await waitFor(() => expect(screen.getByText(/Couldn't load platform diagnostics/)).toBeInTheDocument());
  });
});
