import { describe, it, expect } from 'vitest';
import { counterpartyLabel } from './counterpartyLabel';
import type { CounterpartyType } from '../types';

describe('counterpartyLabel', () => {
  it('reads the same counterparty differently in each direction', () => {
    // The whole reason direction is composed here rather than stored. Same person, opposite
    // readings — a stored label could only ever have been right for one of these.
    expect(counterpartyLabel('PERSON', 'EXPENSE')?.full).toBe('Sent to a person');
    expect(counterpartyLabel('PERSON', 'INCOME')?.full).toBe('Received from a person');
    expect(counterpartyLabel('BUSINESS', 'EXPENSE')?.full).toBe('Paid a business');
    expect(counterpartyLabel('BUSINESS', 'INCOME')?.full).toBe('Received from a business');
  });

  it('keeps the short form direction-free, so a badge never contradicts the amount', () => {
    // The badge is read next to a signed amount. If the short form claimed a direction and the two
    // ever disagreed, the row would argue with itself.
    for (const direction of ['INCOME', 'EXPENSE'] as const) {
      expect(counterpartyLabel('PERSON', direction)?.short).toBe('Person');
      expect(counterpartyLabel('BUSINESS', direction)?.short).toBe('Business');
    }
  });

  it('says nothing at all for UNKNOWN', () => {
    // Roughly one real row in five, plus every row the server has not yet backfilled. A badge
    // reading "unknown" on that many rows is worse than blank space.
    expect(counterpartyLabel('UNKNOWN', 'EXPENSE')).toBeNull();
    expect(counterpartyLabel('UNKNOWN', 'INCOME')).toBeNull();
  });

  it('says nothing for a type this build does not recognise', () => {
    // The server stores this column as a plain string so a newer deploy's value degrades rather
    // than failing a boot (V142). The client keeps that contract instead of printing a raw enum
    // name at somebody.
    const fromNewerServer = 'CHARITY' as CounterpartyType;
    expect(counterpartyLabel(fromNewerServer, 'EXPENSE')).toBeNull();
  });

  it('never returns an empty or enum-shaped string when it returns anything', () => {
    const types: CounterpartyType[] = ['PERSON', 'BUSINESS', 'FINANCIAL_INSTITUTION', 'GOVERNMENT'];
    for (const type of types) {
      for (const direction of ['INCOME', 'EXPENSE'] as const) {
        const label = counterpartyLabel(type, direction);
        expect(label).not.toBeNull();
        expect(label!.short).not.toHaveLength(0);
        expect(label!.full).not.toHaveLength(0);
        // No SCREAMING_SNAKE leaking through to a human.
        expect(label!.short).not.toMatch(/^[A-Z_]+$/);
      }
    }
  });
});
