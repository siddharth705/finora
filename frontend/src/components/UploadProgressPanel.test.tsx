import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { UploadProgressPanel } from './UploadProgressPanel';

describe('UploadProgressPanel', () => {
  it('renders the caller-supplied idle content in the idle state', () => {
    render(<UploadProgressPanel state="idle" progress={0} idle={<button>Choose a file</button>} />);

    expect(screen.getByRole('button', { name: 'Choose a file' })).toBeInTheDocument();
    expect(screen.queryByTestId('upload-progress')).not.toBeInTheDocument();
    expect(screen.queryByTestId('upload-completed')).not.toBeInTheDocument();
  });

  it('shows live percent text below 100 and hides the idle content while uploading', () => {
    render(<UploadProgressPanel state="uploading" progress={42} idle={<button>Choose a file</button>} />);

    expect(screen.getByText('Uploading… 42%')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Choose a file' })).not.toBeInTheDocument();
  });

  // 100% means the bytes finished sending, not that the server has finished reading them --
  // see ProgressCallback's own doc comment in api/endpoints.ts. Getting this wrong here would
  // make the bar claim "done" for however long the server is still parsing a large statement.
  it('reads as "Processing statement…" rather than "100%" once the transfer itself is done', () => {
    render(<UploadProgressPanel state="uploading" progress={100} idle={<button>Choose a file</button>} />);

    expect(screen.getByText('Processing statement…')).toBeInTheDocument();
    expect(screen.queryByText(/100%/)).not.toBeInTheDocument();
  });

  it('shows the completed checkmark and hides both the idle content and the progress bar', () => {
    render(<UploadProgressPanel state="completed" progress={100} idle={<button>Choose a file</button>} />);

    expect(screen.getByTestId('upload-completed')).toBeInTheDocument();
    expect(screen.getByText('Completed')).toBeInTheDocument();
    expect(screen.queryByTestId('upload-progress')).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Choose a file' })).not.toBeInTheDocument();
  });
});
