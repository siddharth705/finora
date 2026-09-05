import { Text } from 'react-native';
import { render, screen } from '@testing-library/react-native';
import { UploadProgressPanel } from './UploadProgressPanel';

describe('UploadProgressPanel', () => {
  it('renders the caller-supplied idle content in the idle state', () => {
    render(<UploadProgressPanel state="idle" progress={0} idle={<Text>Choose a file</Text>} />);

    expect(screen.getByText('Choose a file')).toBeTruthy();
    expect(screen.queryByTestId('upload-progress')).toBeNull();
    expect(screen.queryByTestId('upload-completed')).toBeNull();
  });

  it('shows live percent text below 100 and hides the idle content while uploading', () => {
    render(<UploadProgressPanel state="uploading" progress={42} idle={<Text>Choose a file</Text>} />);

    expect(screen.getByText('Uploading… 42%')).toBeTruthy();
    expect(screen.queryByText('Choose a file')).toBeNull();
  });

  // 100% means the bytes finished sending, not that the server has finished reading them -- see
  // ProgressCallback's own doc comment in api/endpoints.ts. Getting this wrong here would make the
  // bar claim "done" for however long the server is still parsing a large statement.
  it('reads as "Reading statement…" rather than "100%" once the transfer itself is done', () => {
    render(<UploadProgressPanel state="uploading" progress={100} idle={<Text>Choose a file</Text>} />);

    expect(screen.getByText('Reading statement…')).toBeTruthy();
    expect(screen.queryByText(/100%/)).toBeNull();
  });

  it('shows the completed checkmark and hides both the idle content and the progress bar', () => {
    render(<UploadProgressPanel state="completed" progress={100} idle={<Text>Choose a file</Text>} />);

    expect(screen.getByTestId('upload-completed')).toBeTruthy();
    expect(screen.getByText('Completed')).toBeTruthy();
    expect(screen.queryByTestId('upload-progress')).toBeNull();
    expect(screen.queryByText('Choose a file')).toBeNull();
  });
});
