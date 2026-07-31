import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DataTable, type DataTableColumn } from './DataTable';

interface Row {
  id: string;
  name: string;
}

const columns: DataTableColumn<Row>[] = [
  { header: 'Name', render: (row) => row.name },
];

describe('DataTable', () => {
  it('shows a loading row while loading, and no empty-state or data rows', () => {
    render(<DataTable columns={columns} rows={undefined} keyFor={(r) => r.id} loading emptyMessage="Nothing here." />);

    expect(screen.getByText('Loading…')).toBeInTheDocument();
    expect(screen.queryByText('Nothing here.')).not.toBeInTheDocument();
  });

  it('shows the empty message once loading has finished and there are no rows', () => {
    render(<DataTable columns={columns} rows={[]} keyFor={(r) => r.id} loading={false} emptyMessage="Nothing here." />);

    expect(screen.getByText('Nothing here.')).toBeInTheDocument();
    expect(screen.queryByText('Loading…')).not.toBeInTheDocument();
  });

  it('renders one row per item using each column\'s render function', () => {
    const rows: Row[] = [{ id: '1', name: 'Alpha' }, { id: '2', name: 'Beta' }];
    render(<DataTable columns={columns} rows={rows} keyFor={(r) => r.id} loading={false} emptyMessage="Nothing here." />);

    expect(screen.getByText('Alpha')).toBeInTheDocument();
    expect(screen.getByText('Beta')).toBeInTheDocument();
    expect(screen.queryByText('Nothing here.')).not.toBeInTheDocument();
  });

  it('renders one header cell per column, using the header label', () => {
    render(<DataTable columns={columns} rows={[]} keyFor={(r) => r.id} loading={false} emptyMessage="x" />);
    expect(screen.getByRole('columnheader', { name: 'Name' })).toBeInTheDocument();
  });
});
