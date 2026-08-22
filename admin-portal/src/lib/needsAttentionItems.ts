import type { LucideIcon } from 'lucide-react';
import { AlertTriangle, Lock, Tag, Copy } from 'lucide-react';
import type { NeedsAttentionDto } from '../types';

export interface NeedsAttentionItem {
  count: number;
  icon: LucideIcon;
  label: string;
  to: string | null;
  linkLabel: string | null;
}

/**
 * Every field on NeedsAttentionDto turned into a display row, non-zero fields only -- shared by
 * Dashboard.tsx's own NeedsAttentionSection and NotificationBell.tsx (dashboard redesign PR3) so
 * the two surfaces can't silently drift on what "needs attention" means or how many rows are
 * showing. See the backend record's own doc comment for what each field represents.
 */
export function needsAttentionItems(data: NeedsAttentionDto): NeedsAttentionItem[] {
  return [
    {
      count: data.importsWithSkippedRowsToday,
      icon: AlertTriangle,
      label: 'imports had skipped rows today',
      to: '/diagnostics',
      linkLabel: 'View in Diagnostics',
    },
    {
      count: data.lockedAccounts,
      icon: Lock,
      label: 'accounts are currently locked out',
      to: '/users',
      linkLabel: 'Go to Users',
    },
    {
      count: data.transactionsNeedingCategoryReview,
      icon: Tag,
      label: 'transactions still need category review',
      to: null,
      linkLabel: null,
    },
    {
      count: data.transactionsFlaggedAsDuplicates,
      icon: Copy,
      label: 'transactions are flagged as potential duplicates',
      to: null,
      linkLabel: null,
    },
  ].filter((item) => item.count > 0);
}
