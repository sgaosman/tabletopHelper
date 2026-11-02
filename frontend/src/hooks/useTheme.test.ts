import { describe, it, expect, afterEach } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useTheme } from './useTheme';

describe('useTheme', () => {
  afterEach(() => {
    delete document.documentElement.dataset.theme;
    localStorage.removeItem('theme');
  });

  it('starts on Tourmaline when no theme has been chosen', () => {
    const { result } = renderHook(() => useTheme());

    expect(result.current.theme).toBe('tourmaline');
  });

  it('picks up the theme the page was opened with', () => {
    document.documentElement.dataset.theme = 'tavern';

    const { result } = renderHook(() => useTheme());

    expect(result.current.theme).toBe('tavern');
  });

  it('falls back to Tourmaline for a theme it does not know', () => {
    document.documentElement.dataset.theme = 'neon';

    const { result } = renderHook(() => useTheme());

    expect(result.current.theme).toBe('tourmaline');
  });

  it('applies a new theme to the page and saves it on this device', () => {
    const { result } = renderHook(() => useTheme());

    act(() => result.current.setTheme('handbook'));

    expect(result.current.theme).toBe('handbook');
    expect(document.documentElement.dataset.theme).toBe('handbook');
    expect(localStorage.getItem('theme')).toBe('handbook');
  });
});
