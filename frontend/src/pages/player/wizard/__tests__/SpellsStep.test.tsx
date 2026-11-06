import { describe, it, expect, beforeEach } from 'vitest';
import { useState } from 'react';
import { http, HttpResponse } from 'msw';
import { render, screen, waitFor } from '../../../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { server } from '../../../../test/mocks/server';
import { setupAuthToken } from '../../../../test/test-utils';
import SpellsStep from '../SpellsStep';
import type { CharacterClassRef, Spell } from '../../../../types/reference';

const CANTRIPS = ['Fire Bolt', 'Mind Sliver', 'Minor Illusion', 'Prestidigitation'];
const LEVEL_ONE = ['Magic Missile', 'Shield'];

function spell(name: string, level: number): Spell {
  return {
    id: name.toLowerCase().replace(/\s+/g, '-'), name, level, school: 'Evocation',
    castingTime: null, rangeDistance: null, components: null, duration: null,
    concentration: false, ritual: false, description: null, higherLevels: null,
    classes: ['Sorcerer'], damageType: null, damageDice: null, saveAbility: null, source: 'PHB',
  };
}

const sorcerer = {
  id: 'cls-sorcerer', name: 'Sorcerer', isSpellcaster: true, isKnownCaster: true, isPreparedCaster: false,
} as CharacterClassRef;

function Harness({ level = 1 }: { level?: number }) {
  const [selectedCantrips, setSelectedCantrips] = useState<Spell[]>([]);
  const [selectedSpells, setSelectedSpells] = useState<Spell[]>([]);
  const [cantripResults, setCantripResults] = useState<Spell[]>([]);
  const [spellResults, setSpellResults] = useState<Spell[]>([]);
  const [cantripSearch, setCantripSearch] = useState('');
  const [spellSearch, setSpellSearch] = useState('');
  return (
    <SpellsStep
      selectedClass={sorcerer} classEntries={[]} level={level}
      selectedCantrips={selectedCantrips} setSelectedCantrips={setSelectedCantrips}
      selectedSpells={selectedSpells} setSelectedSpells={setSelectedSpells}
      cantripResults={cantripResults} setCantripResults={setCantripResults}
      spellResults={spellResults} setSpellResults={setSpellResults}
      cantripSearch={cantripSearch} setCantripSearch={setCantripSearch}
      spellSearch={spellSearch} setSpellSearch={setSpellSearch}
    />
  );
}

describe('SpellsStep search', () => {
  beforeEach(() => {
    setupAuthToken();
    // Filters by level and name, like the real spell endpoint
    server.use(
      http.get('/api/reference/spells', ({ request }) => {
        const params = new URL(request.url).searchParams;
        const level = Number(params.get('level'));
        const name = params.get('name')?.toLowerCase() ?? '';
        const content = (level === 0 ? CANTRIPS : LEVEL_ONE)
          .filter(n => n.toLowerCase().includes(name))
          .map(n => spell(n, level));
        return HttpResponse.json({ content, totalElements: content.length, totalPages: 1, number: 0, size: 50 });
      }),
    );
  });

  it('narrows the cantrips as you type, with no Search button to press', async () => {
    render(<Harness />);
    await screen.findByRole('button', { name: 'Fire Bolt' });

    await userEvent.type(screen.getByPlaceholderText('Search cantrips...'), 'mind sliver');

    await waitFor(() => expect(screen.queryByRole('button', { name: 'Fire Bolt' })).not.toBeInTheDocument());
    expect(screen.getByRole('button', { name: 'Mind Sliver' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Minor Illusion' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Search' })).not.toBeInTheDocument();
  });

  it('brings the full list back when you clear the search', async () => {
    render(<Harness />);
    await screen.findByRole('button', { name: 'Fire Bolt' });
    const searchBox = screen.getByPlaceholderText('Search cantrips...');
    await userEvent.type(searchBox, 'mind');
    await waitFor(() => expect(screen.queryByRole('button', { name: 'Fire Bolt' })).not.toBeInTheDocument());

    await userEvent.clear(searchBox);

    expect(await screen.findByRole('button', { name: 'Fire Bolt' })).toBeInTheDocument();
  });

  it("chooses a level 5 sorcerer's 1st-level spells only: the later levels' come as choices on the sheet (D159)", async () => {
    render(<Harness level={5} />);

    expect(await screen.findByText('0/4 selected')).toBeInTheDocument();
    expect(screen.getByText('0/2 selected')).toBeInTheDocument();
    expect(screen.getByText('Level 1 Spells')).toBeInTheDocument();
    expect(screen.getByText(/are chosen on your character sheet after creation/)).toBeInTheDocument();
  });
});
