import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter } from '../../test/mocks/handlers';
import type { FeatOptionResponse } from '../../types/character';
import type { Feat, PageResponse, Spell } from '../../types/reference';
import FeatPicker from './FeatPicker';

function spell(name: string, level: number): Spell {
  return {
    id: `spell-${name.toLowerCase().replace(/\s+/g, '-')}`, name, level, school: 'Evocation', castingTime: '1 action',
    rangeDistance: '60 feet', components: null, duration: 'Instantaneous', concentration: false, ritual: false,
    description: null, higherLevels: null, classes: ['Wizard'], damageType: null, damageDice: null,
    saveAbility: null, source: 'PHB',
  };
}

const CANTRIPS = [spell('Fire Bolt', 0), spell('Light', 0), spell('Mage Hand', 0)];

// A feat that grants two wizard cantrips of the player's choice, as the 5etools data writes it
const INITIATE: Feat = {
  id: 'feat-initiate', name: 'Wizard Initiate', source: 'TEST',
  grantsFeatures: [{ name: 'Wizard', known: { _: [{ choose: 'level=0|class=Wizard', count: 2 }] } }],
};

const GRAPPLER: Feat = {
  id: 'feat-grappler', name: 'Grappler', source: 'PHB', prerequisite: [{ abilities: [{ ability: 'STR', minimum: 13 }] }],
};

// +1 to Strength or Dexterity, as Athlete gives
const ATHLETE: Feat = {
  id: 'feat-athlete', name: 'Athlete', source: 'PHB',
  abilityScoreIncrease: [{ choose: { from: ['STR', 'DEX'], amount: 1 } }],
};

function option(feat: Feat, over: Partial<FeatOptionResponse> = {}): FeatOptionResponse {
  return { featId: feat.id, name: feat.name, source: feat.source, eligible: true, unmet: [], taken: false,
    repeatable: false, ...over };
}

describe('FeatPicker', () => {
  let searched: URLSearchParams[] = [];
  let optionsAsked = 0;

  beforeEach(() => {
    setupAuthToken();
    searched = [];
    optionsAsked = 0;
    server.use(
      http.get('/api/reference/feats', () => HttpResponse.json([INITIATE, GRAPPLER, ATHLETE])),
      http.get('/api/characters/:id/feat-options', () => {
        optionsAsked++;
        return HttpResponse.json([
          option(INITIATE),
          option(GRAPPLER, { eligible: false, unmet: ['Strength 13 (you have 12)'], prerequisite: 'Strength 13' }),
          option(ATHLETE),
        ]);
      }),
      http.get('/api/reference/spells', ({ request }) => {
        const params = new URL(request.url).searchParams;
        searched.push(params);
        return HttpResponse.json({ content: CANTRIPS, totalElements: 3, totalPages: 1, number: 0, size: 50 } satisfies PageResponse<Spell>);
      }),
    );
  });

  it("refuses a feat whose prerequisite the server says isn't met, naming what is missing, after those that can be taken", async () => {
    render(<FeatPicker character={makeMockCharacter('char-001', 'Mira', 'Fighter', 4)} onSelectionChange={() => {}} />);

    const grappler = await screen.findByRole('button', { name: /Grappler/ });
    expect(grappler).toBeDisabled();
    expect(screen.getByText('Strength 13 (you have 12)')).toBeInTheDocument();
    const names = screen.getAllByRole('button').map(button => button.textContent ?? '');
    expect(names.findIndex(name => name.includes('Grappler')))
      .toBeGreaterThan(names.findIndex(name => name.includes('Wizard Initiate')));
    expect(optionsAsked).toBe(1);
  });

  it("lets a DM's grant pick it, showing what isn't met (D158)", async () => {
    const onSelectionChange = vi.fn();
    render(<FeatPicker character={makeMockCharacter('char-001', 'Mira', 'Fighter', 4)} onSelectionChange={onSelectionChange}
      ignorePrerequisites />);
    const user = userEvent.setup();

    expect(await screen.findByText('Prerequisite not met: Strength 13 (you have 12)')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: /Grappler/ }));
    await waitFor(() => expect(onSelectionChange).toHaveBeenLastCalledWith({
      type: 'feat', featId: 'feat-grappler', featAbility: undefined,
    }));
  });

  it("starts a feat's choices afresh when another feat is picked", async () => {
    const onSelectionChange = vi.fn();
    render(<FeatPicker character={makeMockCharacter('char-001', 'Mira', 'Fighter', 4)} onSelectionChange={onSelectionChange} />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /Athlete/ }));
    await user.click(screen.getByRole('button', { name: /^STR/ }));
    await waitFor(() => expect(onSelectionChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ featId: 'feat-athlete', featAbility: 'STR' })));

    await user.click(screen.getByRole('button', { name: /Back/ }));
    expect(onSelectionChange).toHaveBeenLastCalledWith(null);
    await user.click(screen.getByRole('button', { name: /Athlete/ }));
    // The ability is to choose again, so nothing is selected yet
    await waitFor(() => expect(onSelectionChange).toHaveBeenLastCalledWith(null));
    expect(screen.getByRole('button', { name: /^STR/ })).not.toHaveClass('bg-ink');
  });

  it('picks each spell slot through the spell picker, never the same spell twice, and sends their IDs', async () => {
    const onSelectionChange = vi.fn();
    render(<FeatPicker character={makeMockCharacter('char-001', 'Mira', 'Fighter', 4)} onSelectionChange={onSelectionChange} />);
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: /Wizard Initiate/ }));
    const slots = () => screen.getAllByRole('button', { name: 'Choose a Wizard cantrip...' });
    await user.click(slots()[0]);
    await user.click(await screen.findByRole('button', { name: 'Fire Bolt' }));
    expect(searched[0].get('className')).toBe('Wizard');
    expect(searched[0].get('level')).toBe('0');

    await user.click(slots()[0]);
    expect(await screen.findByRole('button', { name: /Fire Bolt.*Chosen/ })).toBeDisabled();
    await user.click(screen.getByRole('button', { name: 'Light' }));

    await waitFor(() => expect(onSelectionChange).toHaveBeenLastCalledWith(expect.objectContaining({
      type: 'feat', featId: 'feat-initiate', spellIds: ['spell-fire-bolt', 'spell-light'],
    })));
  });
});
