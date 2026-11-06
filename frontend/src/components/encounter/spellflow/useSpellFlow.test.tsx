import { describe, it, expect } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClientProvider } from '@tanstack/react-query';
import { AxiosError, AxiosHeaders } from 'axios';
import { http, HttpResponse } from 'msw';
import type { ReactNode } from 'react';
import { createTestQueryClient } from '../../../test/test-utils';
import { server } from '../../../test/mocks/server';
import { makeMockCastSpellResponse } from '../../../test/mocks/handlers';
import { SERVER_ERROR_MESSAGE } from '../../../api/errors';
import type { SpellTargetingInfo } from '../../../types/reference';
import useSpellFlow, { takesNoTargets } from './useSpellFlow';

const ANYONE: SpellTargetingInfo = {
  maxTargets: -1, selfOnly: false, canTargetSelf: true, canTargetAllies: true, canTargetEnemies: true,
  targetType: 'SINGLE_TARGET', hasRepeatEffect: false,
};
const NOBODY: SpellTargetingInfo = { ...ANYONE, canTargetSelf: false, canTargetAllies: false, canTargetEnemies: false };

function targeting(info: SpellTargetingInfo | null) {
  server.use(http.get('/api/reference/spells/targeting', () =>
    (info ? HttpResponse.json(info) : new HttpResponse(null, { status: 404 }))));
}

function renderFlow(allowManualModifiers = false) {
  const queryClient = createTestQueryClient();
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
  );
  return renderHook(() => useSpellFlow<'spell'>('spell', { allowManualModifiers }), { wrapper });
}

function serverError(status: number, data: unknown) {
  return new AxiosError('failed', String(status), undefined, undefined, {
    status, statusText: '', data, headers: {}, config: { headers: new AxiosHeaders() },
  });
}

describe('takesNoTargets', () => {
  it('is true only for a spell that can target nobody', () => {
    expect(takesNoTargets(NOBODY)).toBe(true);
    expect(takesNoTargets({ ...NOBODY, selfOnly: true })).toBe(false);
    expect(takesNoTargets(ANYONE)).toBe(false);
    expect(takesNoTargets(null)).toBe(false);
  });
});

describe('useSpellFlow', () => {
  it('starts on the step given, with targeting not yet looked up', () => {
    const { result } = renderFlow();

    expect(result.current.step).toBe('spell');
    expect(result.current.targetingLoading).toBe(true);
    expect(result.current.selectedTargets).toEqual([]);
    expect(result.current.advantage).toBeNull();
  });

  it('moves to the target step once the targeting is looked up', async () => {
    targeting({ ...ANYONE, maxTargets: 2 });
    const { result } = renderFlow();

    await act(() => result.current.lookUpTargeting('Bless', 1));

    expect(result.current.step).toBe('target');
    expect(result.current.targetingLoading).toBe(false);
    expect(result.current.targetingInfo?.maxTargets).toBe(2);
  });

  it('skips to confirm when the spell targets nobody', async () => {
    targeting(NOBODY);
    const { result } = renderFlow();

    await act(() => result.current.lookUpTargeting('Alarm', 1));

    expect(result.current.step).toBe('confirm');
    expect(result.current.takesNoTargets).toBe(true);
  });

  it('leaves the targets unrestricted when the lookup fails', async () => {
    targeting(null);
    const { result } = renderFlow();

    await act(() => result.current.lookUpTargeting('Homebrew Bolt', 1));

    expect(result.current.step).toBe('target');
    expect(result.current.targetingInfo).toBeNull();
    expect(result.current.targetingLoading).toBe(false);
  });

  it('toggles targets up to the limit, and clears them on a new lookup', async () => {
    targeting({ ...ANYONE, maxTargets: 2 });
    const { result } = renderFlow();
    await act(() => result.current.lookUpTargeting('Bless', 1));

    act(() => result.current.toggleTarget('a'));
    act(() => result.current.toggleTarget('b'));
    act(() => result.current.toggleTarget('c'));
    expect(result.current.selectedTargets).toEqual(['a', 'b']);
    act(() => result.current.toggleTarget('a'));
    expect(result.current.selectedTargets).toEqual(['b']);

    await act(() => result.current.lookUpTargeting('Bless', 2));
    expect(result.current.selectedTargets).toEqual([]);
  });

  it('sends the Manual Modifiers typed in, leaving out blank ones (D164)', () => {
    const { result } = renderFlow(true);

    act(() => result.current.setOverrideSaveDC('16'));

    expect(result.current.overrides()).toEqual({ overrideSpellAttackBonus: undefined, overrideSpellSaveDC: 16 });
  });

  it('sends no Manual Modifiers when they are not allowed', () => {
    const { result } = renderFlow(false);

    act(() => result.current.setOverrideAttackBonus('9'));

    expect(result.current.overrides()).toEqual({ overrideSpellAttackBonus: undefined, overrideSpellSaveDC: undefined });
  });

  it('keeps the result of a request that succeeds', async () => {
    const { result } = renderFlow();
    const response = makeMockCastSpellResponse({ resultSummary: 'Done it.' });

    await act(() => result.current.submit(async () => response));

    expect(result.current.result).toBe(response);
    expect(result.current.error).toBe('');
  });

  it("shows the server's message for a refused request, and lets it be sent again", async () => {
    const { result } = renderFlow();

    await act(() => result.current.submit(() => Promise.reject(serverError(409, { error: 'Hold Person has ended' }))));

    expect(result.current.error).toBe('Hold Person has ended');
    expect(result.current.submitting).toBe(false);
    expect(result.current.result).toBeNull();
  });

  it('shows the generic message for a server failure', async () => {
    const { result } = renderFlow();

    await act(() => result.current.submit(() => Promise.reject(serverError(500, 'boom'))));

    await waitFor(() => expect(result.current.error).toBe(SERVER_ERROR_MESSAGE));
  });
});
