import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useTypeAhead } from './useTypeAhead';

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>(r => { resolve = r; });
  return { promise, resolve };
}

function renderTypeAhead(fetchResults: () => Promise<string[]> | undefined, applyResults = vi.fn()) {
  const view = renderHook(({ query }) => useTypeAhead(query, fetchResults, applyResults), {
    initialProps: { query: '' },
  });
  return { ...view, applyResults };
}

describe('useTypeAhead', () => {
  beforeEach(() => {
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('does not search on the first render', async () => {
    const fetchResults = vi.fn(() => Promise.resolve(['Fire Bolt']));
    renderTypeAhead(fetchResults);

    await act(() => vi.advanceTimersByTimeAsync(1000));

    expect(fetchResults).not.toHaveBeenCalled();
  });

  it('searches once typing pauses, not on every keystroke', async () => {
    const fetchResults = vi.fn(() => Promise.resolve(['Mind Sliver']));
    const { rerender, applyResults } = renderTypeAhead(fetchResults);

    rerender({ query: 'm' });
    rerender({ query: 'mi' });
    rerender({ query: 'min' });
    await act(() => vi.advanceTimersByTimeAsync(249));
    expect(fetchResults).not.toHaveBeenCalled();

    await act(() => vi.advanceTimersByTimeAsync(1));
    expect(fetchResults).toHaveBeenCalledTimes(1);
    expect(applyResults).toHaveBeenCalledWith(['Mind Sliver']);
  });

  it('ignores an earlier search that finishes after a later one', async () => {
    const fire = deferred<string[]>();
    const fireBolt = deferred<string[]>();
    const fetchResults = vi.fn<() => Promise<string[]>>()
      .mockReturnValueOnce(fire.promise)
      .mockReturnValueOnce(fireBolt.promise);
    const { rerender, applyResults } = renderTypeAhead(fetchResults);

    rerender({ query: 'fire' });
    await act(() => vi.advanceTimersByTimeAsync(250));
    rerender({ query: 'fire bolt' });
    await act(() => vi.advanceTimersByTimeAsync(250));
    await act(async () => fireBolt.resolve(['Fire Bolt']));
    await act(async () => fire.resolve(['Fire Bolt', 'Fireball', 'Fire Shield']));

    expect(applyResults).toHaveBeenCalledTimes(1);
    expect(applyResults).toHaveBeenCalledWith(['Fire Bolt']);
  });

  it('searches straight away on searchNow and drops the pending search', async () => {
    const fetchResults = vi.fn(() => Promise.resolve(['Shield']));
    const { result, rerender } = renderTypeAhead(fetchResults);

    rerender({ query: 'shield' });
    await act(async () => result.current.searchNow());
    expect(fetchResults).toHaveBeenCalledTimes(1);

    await act(() => vi.advanceTimersByTimeAsync(500));
    expect(fetchResults).toHaveBeenCalledTimes(1);
  });

  it('reports searching only while the newest search is in flight', async () => {
    const pending = deferred<string[]>();
    const { result } = renderTypeAhead(() => pending.promise);

    act(() => result.current.searchNow());
    expect(result.current.searching).toBe(true);

    await act(async () => pending.resolve(['Bless']));
    expect(result.current.searching).toBe(false);
  });

  it('skips the search when there is nothing to search', async () => {
    const { result, rerender, applyResults } = renderTypeAhead(() => undefined);

    rerender({ query: 'bless' });
    await act(() => vi.advanceTimersByTimeAsync(250));

    expect(applyResults).not.toHaveBeenCalled();
    expect(result.current.searching).toBe(false);
  });

  it('keeps the current results when a search fails', async () => {
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    const { result, applyResults } = renderTypeAhead(() => Promise.reject(new Error('offline')));

    await act(async () => result.current.searchNow());

    expect(applyResults).not.toHaveBeenCalled();
    expect(result.current.searching).toBe(false);
    expect(warn).toHaveBeenCalled();
    warn.mockRestore();
  });
});
