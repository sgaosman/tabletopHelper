import { describe, it, expect, beforeEach, vi } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter } from '../../test/mocks/handlers';
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
  grantsFeatures: JSON.stringify([{ name: 'Wizard', known: { _: [{ choose: 'level=0|class=Wizard', count: 2 }] } }]),
};

describe("FeatPicker's spell slots", () => {
  let searched: URLSearchParams[] = [];

  beforeEach(() => {
    setupAuthToken();
    searched = [];
    server.use(
      http.get('/api/reference/feats', () => HttpResponse.json([INITIATE])),
      http.get('/api/reference/spells', ({ request }) => {
        const params = new URL(request.url).searchParams;
        searched.push(params);
        return HttpResponse.json({ content: CANTRIPS, totalElements: 3, totalPages: 1, number: 0, size: 50 } satisfies PageResponse<Spell>);
      }),
    );
  });

  it('picks each slot through the spell picker, never the same spell twice, and sends their IDs', async () => {
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
