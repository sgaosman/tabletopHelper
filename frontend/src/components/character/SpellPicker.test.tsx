import { describe, it, expect, beforeEach, vi } from 'vitest';
import { useState } from 'react';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import type { PageResponse, Spell } from '../../types/reference';
import SpellPicker, { type SpellFilter, type SpellState } from './SpellPicker';

function spell(name: string, level: number): Spell {
  return {
    id: `spell-${name.toLowerCase().replace(/\s+/g, '-')}`, name, level, school: 'Evocation', castingTime: '1 action',
    rangeDistance: '60 feet', components: null, duration: 'Instantaneous', concentration: false, ritual: false,
    description: null, higherLevels: null, classes: ['Wizard'], damageType: null, damageDice: null,
    saveAbility: null, source: 'PHB',
  };
}

const LIST: Spell[] = [
  spell('Fire Bolt', 0), spell('Light', 0), spell('Mage Hand', 0),
  spell('Magic Missile', 1), spell('Shield', 1), spell('Sleep', 1), spell('Misty Step', 2), spell('Web', 2),
];

/** Every request the spell search was sent, by its query parameters. */
let requests: URLSearchParams[] = [];

function Harness({ initial = [], onChange, ...props }: {
  initial?: string[];
  onChange?: (value: string[], spell: Spell) => void;
  filter: SpellFilter;
  max?: number;
  statusOf?: (spell: Spell, chosen: boolean) => SpellState | undefined;
  localSpells?: Spell[];
  debounceMs?: number;
}) {
  const [value, setValue] = useState<string[]>(initial);
  return (
    <SpellPicker {...props} value={value} onChange={(next, changed) => { setValue(next); onChange?.(next, changed); }} />
  );
}

describe('SpellPicker', () => {
  beforeEach(() => {
    setupAuthToken();
    requests = [];
    server.use(http.get('/api/reference/spells', ({ request }) => {
      const params = new URL(request.url).searchParams;
      requests.push(params);
      const levels = (params.get('level') ?? '').split(',').filter(Boolean).map(Number);
      const name = params.get('name')?.toLowerCase() ?? '';
      const content = LIST.filter(s => (levels.length === 0 || levels.includes(s.level)) && s.name.toLowerCase().includes(name));
      return HttpResponse.json({ content, totalElements: content.length, totalPages: 1, number: 0, size: 50 } satisfies PageResponse<Spell>);
    }));
  });

  it('chooses up to its limit, and a chosen spell is taken off again by a second click', async () => {
    const onChange = vi.fn();
    render(<Harness filter={{ className: 'Wizard', levels: [0] }} max={2} onChange={onChange} />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Fire Bolt' }));
    await user.click(screen.getByRole('button', { name: 'Light' }));

    expect(screen.getByRole('button', { name: /Fire Bolt.*Chosen/ })).toHaveAttribute('aria-pressed', 'true');
    // Two of two: Mage Hand waits until one goes
    expect(screen.getByRole('button', { name: 'Mage Hand' })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: /Fire Bolt/ }));
    expect(onChange).toHaveBeenLastCalledWith(['Light'], LIST[0]);
    expect(screen.getByRole('button', { name: 'Mage Hand' })).toBeEnabled();
  });

  it('shows what statusOf says of each spell, and a disabled one cannot be chosen', async () => {
    const statusOf = (s: Spell, chosen: boolean): SpellState | undefined =>
      s.name === 'Shield' ? { status: 'Known', disabled: true }
        : s.name === 'Sleep' ? { status: 'Always', disabled: true }
          : chosen ? { status: 'Prepared' } : undefined;
    render(<Harness filter={{ className: 'Wizard', levels: [1] }} statusOf={statusOf} initial={['Magic Missile']} />);

    expect(await screen.findByRole('button', { name: /Shield.*Known/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Sleep.*Always/ })).toBeDisabled();
    expect(screen.getByRole('button', { name: /Magic Missile.*Prepared/ })).toBeEnabled();
  });

  it('asks for all its levels in one request, and narrows them with the level filter', async () => {
    render(<Harness filter={{ className: 'Wizard', levels: [1, 2] }} />);
    const user = userEvent.setup();

    expect(await screen.findByRole('button', { name: 'Misty Step' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Shield' })).toBeInTheDocument();
    expect(requests).toHaveLength(1);
    expect(requests[0].get('level')).toBe('1,2');
    expect(requests[0].get('className')).toBe('Wizard');

    await user.selectOptions(screen.getByRole('combobox', { name: 'Spell level' }), 'Level 2');

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Shield' })).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Web' })).toBeInTheDocument();
    expect(requests.at(-1)?.get('level')).toBe('2');
  });

  it('has no level filter for a single level', async () => {
    render(<Harness filter={{ className: 'Wizard', levels: [0] }} />);

    await screen.findByRole('button', { name: 'Light' });
    expect(screen.queryByRole('combobox', { name: 'Spell level' })).not.toBeInTheDocument();
  });

  it('offers only the spells it names, in any case', async () => {
    render(<Harness filter={{ levels: [0, 1, 2], names: ['shield', 'Web'] }} />);

    expect(await screen.findByRole('button', { name: 'Shield' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Web' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Sleep' })).not.toBeInTheDocument();
    // A named list is asked for whole, since the server can't filter by name
    expect(requests[0].get('size')).toBe('1000');
    expect(requests[0].has('className')).toBe(false);
  });

  it('searches once typing stops, not at every key, with the last results up meanwhile', async () => {
    render(<Harness filter={{ className: 'Wizard', levels: [0, 1, 2] }} />);
    const user = userEvent.setup();
    await screen.findByRole('button', { name: 'Fire Bolt' });

    await user.type(screen.getByRole('textbox', { name: 'Search spells' }), 'mis');
    // The earlier results stay up until the new ones arrive
    expect(screen.getByRole('button', { name: 'Fire Bolt' })).toBeInTheDocument();

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Fire Bolt' })).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Magic Missile' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Misty Step' })).toBeInTheDocument();
    expect(requests.map(r => r.get('name'))).toEqual([null, 'mis']);
  });

  it('searches at once on Enter', async () => {
    render(<Harness filter={{ className: 'Wizard', levels: [0, 1, 2] }} debounceMs={60_000} />);
    const user = userEvent.setup();
    await screen.findByRole('button', { name: 'Fire Bolt' });

    await user.type(screen.getByRole('textbox', { name: 'Search spells' }), 'web{Enter}');

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Fire Bolt' })).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Web' })).toBeInTheDocument();
    expect(requests.at(-1)?.get('name')).toBe('web');
  });

  it('filters a list it is given on the page, without searching', async () => {
    const book = [spell('Magic Missile', 1), spell('Shield', 1), spell('Misty Step', 2)];
    render(<Harness filter={{ levels: [1, 2] }} localSpells={book} />);
    const user = userEvent.setup();

    expect(screen.getByRole('button', { name: 'Shield' })).toBeInTheDocument();
    await user.type(screen.getByRole('textbox', { name: 'Search spells' }), 'mi');

    expect(screen.getByRole('button', { name: 'Magic Missile' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Misty Step' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Shield' })).not.toBeInTheDocument();
    expect(requests).toHaveLength(0);
  });
});
