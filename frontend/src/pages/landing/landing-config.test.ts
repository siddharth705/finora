import { describe, expect, it } from 'vitest';
import { heroBadges, heroIntelligence, heroScore } from './landing-config';

describe('hero cinematic copy', () => {
  it('keeps the health score within a real 0-100 range', () => {
    expect(heroScore.value).toBeGreaterThanOrEqual(0);
    expect(heroScore.value).toBeLessThanOrEqual(100);
    expect(heroScore.label.length).toBeGreaterThan(0);
    expect(heroScore.delta.length).toBeGreaterThan(0);
  });

  it('has at least one intelligence-scan step', () => {
    expect(heroIntelligence.steps.length).toBeGreaterThan(0);
    heroIntelligence.steps.forEach((step) => expect(step.length).toBeGreaterThan(0));
  });

  it("keeps the salary badge consistent with the dashboard mock's own salary figure", () => {
    // DashboardMock's TRANSACTIONS lists "Salary Credit" at +₹1,24,500 -- a badge quoting a
    // different salary number on the same screen would be the kind of internal inconsistency
    // DashboardMock's own file comment calls out as the first thing a finance-literate visitor
    // notices.
    const salaryBadge = heroBadges.find((b) => b.label.includes('Salary'));
    expect(salaryBadge?.label).toContain('1,24,500');
  });

  it('has at least three floating badges', () => {
    expect(heroBadges.length).toBeGreaterThanOrEqual(3);
  });
});
