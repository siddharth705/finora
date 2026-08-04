import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { InlineRuleForm } from './RulesSection';
import type { CreateRuleRequest } from '../../types';

const BLANK: CreateRuleRequest = {
  field: 'DESCRIPTION', operator: 'CONTAINS', comparisonValue: '', actionType: 'ASSIGN_CATEGORY', actionValue: '', priority: 100,
};

/**
 * Bug fix: InlineRuleForm's Field/Operator/Action type selects had no accessible name at all -- a
 * real, critical axe "select-name" violation (a screen reader announced each one as nothing more
 * than "combo box", indistinguishable from the other two). Confirmed via axe against an isolated
 * repro before adding aria-label to each; this pins the fix so it can't silently regress.
 */
describe('InlineRuleForm', () => {
  it('gives every select an accessible name', () => {
    render(
      <InlineRuleForm initial={BLANK} submitting={false} error={null} onCancel={vi.fn()} onSubmit={vi.fn()} />
    );

    expect(screen.getByRole('combobox', { name: 'Field' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Operator' })).toBeInTheDocument();
    expect(screen.getByRole('combobox', { name: 'Action type' })).toBeInTheDocument();
  });
});
