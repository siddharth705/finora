import { describe, it, expect, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useSavedViews } from './useSavedViews';

interface Filters {
  [key: string]: string;
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

  /**
   * Bug 46. save()/remove() used to compute their result from `views` as captured in the closure
   * from the last render -- that closure does not update again until React actually re-renders, so
   * two calls landing before a render could run (e.g. a fast Enter-then-click, or a double-click on
   * the same save button) both read the SAME pre-update `views`. The second call's setViews(next)
   * silently overwrote whatever the first call had just saved.
   */
  it('two saves issued back-to-back, before a re-render, both take effect -- not just the last one', () => {
    const { result } = renderHook(() => useSavedViews<Filters>('test-key'));

    // Both calls run inside ONE act(), so React has not re-rendered (and therefore not refreshed
    // any render-time closure) between them -- exactly the window the bug lived in.
    act(() => {
      result.current.save('First', { q: 'a', status: '' });
      result.current.save('Second', { q: 'b', status: '' });
    });

    expect(result.current.views.map((v) => v.name)).toEqual(['First', 'Second']);
    const stored = JSON.parse(localStorage.getItem('test-key')!);
    expect(stored.map((v: SavedViewLike) => v.name)).toEqual(['First', 'Second']);
  });

  it('a save immediately followed by a remove, before a re-render, applies both in order', () => {
    const { result } = renderHook(() => useSavedViews<Filters>('test-key'));
    act(() => result.current.save('Keep', { q: '', status: '' }));

    act(() => {
      result.current.save('Drop', { q: '', status: '' });
      result.current.remove('Drop');
    });

    expect(result.current.views.map((v) => v.name)).toEqual(['Keep']);
  });
});

interface SavedViewLike {
  name: string;
}
