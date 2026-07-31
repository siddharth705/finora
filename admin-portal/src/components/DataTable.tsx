import type { ReactNode } from 'react';

/**
 * A single column's header text plus how to render each row's cell for it. `render` gets the
 * whole row rather than a pre-extracted field so callers can compose JSX (badges, nested
 * flex layouts, action buttons) exactly like Banks/GlobalRules/Users already did before this
 * was extracted -- this only removes the repeated table/thead/tbody/loading/empty scaffolding
 * around that per-cell JSX, not the JSX itself.
 */
export interface DataTableColumn<T> {
  header: string;
  render: (row: T) => ReactNode;
  headerClassName?: string;
  cellClassName?: string;
}

/**
 * Shared table shell for every admin CRUD page backed by a flat list (Banks, Global Rules,
 * Users, and every module built on top of this pattern going forward). Handles the
 * loading/empty/populated states once instead of each page re-writing its own
 * `{isLoading && ...}` / `{!isLoading && rows.length === 0 && ...}` pair. Not used by Roles &
 * Permissions, which is a card grid rather than a flat table -- this is deliberately just for
 * the tabular case, not a catch-all replacement for every list layout in the app.
 */
export function DataTable<T>({
  columns, rows, keyFor, loading, emptyMessage,
}: {
  columns: DataTableColumn<T>[];
  rows: T[] | undefined;
  keyFor: (row: T) => string;
  loading: boolean;
  emptyMessage: string;
}) {
  return (
    <div className="bg-card border border-border rounded-xl2 shadow-card overflow-hidden">
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b border-border text-left text-xs text-muted uppercase tracking-wide">
            {columns.map((col) => (
              <th key={col.header} className={`px-4 py-3 font-medium ${col.headerClassName ?? ''}`}>
                {col.header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {loading && (
            <tr>
              <td colSpan={columns.length} className="px-4 py-8 text-center text-muted">Loading…</td>
            </tr>
          )}
          {!loading && (rows ?? []).length === 0 && (
            <tr>
              <td colSpan={columns.length} className="px-4 py-8 text-center text-muted">{emptyMessage}</td>
            </tr>
          )}
          {rows?.map((row) => (
            <tr key={keyFor(row)} className="border-b border-border last:border-b-0 hover:bg-bg">
              {columns.map((col) => (
                <td key={col.header} className={`px-4 py-3 ${col.cellClassName ?? ''}`}>
                  {col.render(row)}
                </td>
              ))}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
