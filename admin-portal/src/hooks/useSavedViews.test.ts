import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSavedViews } from './useSavedViews';

interface Filters {
  q: string;
  status: string;
}

describe('useSavedViews', () => {
  beforeEach(() => {
    localStorage.clear();
  });

  it('starts empty when nothing is saved under this key yet', () => {
    const { result } = renderHook(() => useSavedViews<Filters>('test-key'));
    expect(result.current.views).toEqual([]);
  });

  it('saves a view and persists it to localStorage under the given key', () => {
    const { result } = renderHook(() => useSavedViews<Filters>('test-key'));

    act(() => result.current.save('My View', { q: 'amazon', status: 'ACTIVE' }));

    expect(result.current.views).toEqual([{ name: 'My View', values: { q: 'amazon', status: 'ACTIVE' } }]);
    const stored = JSON.parse(localStorage.getItem('test-key')!);
    expect(stored).toEqual([{ name: 'My View', values: { q: 'amazon', status: 'ACTIVE' } }]);
  });

  it('overwrites an existing view with the same name instead of duplicating it', () => {
    const { result } = renderHook(() => useSavedViews<Filters>('test-key'));

    act(() => result.current.save('My View', { q: 'amazon', status: 'ACTIVE' }));
    act(() => result.current.save('My View', { q: 'flipkart', status: 'SUSPENDED' }));

    expect(result.current.views).toHaveLength(1);
    expect(result.current.views[0].values).toEqual({ q: 'flipkart', status: 'SUSPENDED' });
  });

  it('ignores a blank/whitespace-only name', () => {
    const { result } = renderHook(() => useSavedViews<Filters>('test-key'));

    act(() => result.current.save('   ', { q: 'amazon', status: '' }));

    expect(result.current.views).toEqual([]);
  });

  it('removes a view by name', () => {
    const { result } = renderHook(() => useSavedViews<Filters>('test-key'));
    act(() => result.current.save('Keep', { q: '', status: '' }));
    act(() => result.current.save('Drop', { q: '', status: '' }));

    act(() => result.current.remove('Drop'));

    expect(result.current.views.map((v) => v.name)).toEqual(['Keep']);
  });

  it('keeps views under different storage keys separate', () => {
    const { result: usersViews } = renderHook(() => useSavedViews<Filters>('key-a'));
    const { result: auditViews } = renderHook(() => useSavedViews<Filters>('key-b'));

    act(() => usersViews.current.save('A View', { q: 'a', status: '' }));

    expect(usersViews.current.views).toHaveLength(1);
    expect(auditViews.current.views).toHaveLength(0);
  });

  it('recovers gracefully from a corrupt value already stored under the key', () => {
    localStorage.setItem('test-key', 'not valid json{{{');

    const { result } = renderHook(() => useSavedViews<Filters>('test-key'));

    expect(result.current.views).toEqual([]);
  });
});
