import { ChevronLeft, ChevronRight } from 'lucide-react';

/**
 * Shared "Showing X-Y of Z" + prev/next control, extracted from Users.tsx (the first admin page
 * to need real server-side pagination) so the next paginated module doesn't rebuild it. Renders
 * nothing when there's nothing to page through, matching Users.tsx's original
 * `data.totalElements > 0` guard.
 */
export function Pagination({
  page, totalPages, totalElements, pageSize, onPageChange,
}: {
  page: number;
  totalPages: number;
  totalElements: number;
  pageSize: number;
  onPageChange: (page: number) => void;
}) {
  if (totalElements === 0) return null;

  return (
    <div className="flex items-center justify-between mt-4 text-sm text-muted">
      <span>
        Showing {page * pageSize + 1}–{Math.min((page + 1) * pageSize, totalElements)} of {totalElements}
      </span>
      <div className="flex items-center gap-2">
        <button
          type="button"
          disabled={page === 0}
          onClick={() => onPageChange(page - 1)}
          className="w-8 h-8 rounded-lg border border-border bg-card flex items-center justify-center disabled:opacity-40"
        >
          <ChevronLeft size={15} />
        </button>
        <span>Page {page + 1} of {Math.max(totalPages, 1)}</span>
        <button
          type="button"
          disabled={page + 1 >= totalPages}
          onClick={() => onPageChange(page + 1)}
          className="w-8 h-8 rounded-lg border border-border bg-card flex items-center justify-center disabled:opacity-40"
        >
          <ChevronRight size={15} />
        </button>
      </div>
    </div>
  );
}
