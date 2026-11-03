import { describe, it, expect, beforeEach, onTestFinished } from 'vitest';
import { screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { Route, Routes } from 'react-router-dom';
import { render, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { makeMockCharacter, makeMockPendingChoice, makeMockResourcePool } from '../../test/mocks/handlers';
import CharacterSheetPage from './CharacterSheetPage';

function renderSheet(characterId = 'char-001') {
  window.history.pushState({}, '', `/player/characters/${characterId}`);
  return render(
    <Routes>
      <Route path="/player/characters/:characterId" element={<CharacterSheetPage />} />
    </Routes>,
  );
}

/** Records the method and path of every request from now until the test ends. */
function recordRequests() {
  const requests: string[] = [];
  const record = ({ request }: { request: Request }) => {
    requests.push(`${request.method} ${new URL(request.url).pathname}`);
  };
  server.events.on('request:start', record);
  onTestFinished(() => server.events.removeListener('request:start', record));
  return requests;
}

/** Answers one command with the character given, recording each body it is sent. */
function recordCommand(method: 'post' | 'put' | 'patch', path: string, answer: object) {
  const bodies: unknown[] = [];
  server.use(http[method](`/api/characters/:id/${path}`, async ({ request }) => {
    // A command without a body, such as a long rest, is recorded as null
    bodies.push(await request.clone().text() ? await request.json() : null);
    return HttpResponse.json(answer);
  }));
  return bodies;
}

const hurtFighter = { ...makeMockCharacter('char-001', 'Gimli', 'Fighter', 5), hpCurrent: 12 };

describe('CharacterSheetPage', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('shows the character a long rest returns without loading it again; the server works the rest out (D158)', async () => {
    server.use(http.get('/api/characters/:id', () => HttpResponse.json(hurtFighter)));
    const bodies = recordCommand('post', 'long-rest', makeMockCharacter('char-001', 'Gimli', 'Fighter', 5));
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('12/30')).toBeInTheDocument();
    const requests = recordRequests();

    await user.click(screen.getByTitle('Long Rest'));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Long Rest' }));

    expect(await screen.findByText('30/30')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Long rest taken');
    expect(bodies).toEqual([null]);
    expect(requests).toEqual(['POST /api/characters/char-001/long-rest']);
  });

  it('shows the levelled character a level-up returns without loading it again', async () => {
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('Test Character')).toBeInTheDocument();
    const requests = recordRequests();

    await user.click(screen.getByTitle('Level Up'));
    const confirm = within(screen.getByRole('dialog', { name: 'Level Up' })).getByRole('button', { name: 'Level Up' });
    // Enabled once the classes have loaded, with the character's current class selected
    await waitFor(() => expect(confirm).toBeEnabled());
    await user.click(confirm);

    expect(await screen.findByText('Level up to 6 complete!')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('heading', { name: 'Gimli' })).toBeInTheDocument();
    expect(requests).toEqual([
      'GET /api/characters/char-001/eligible-classes',
      'POST /api/characters/char-001/level-up',
    ]);
  });

  it('names the hit dice steppers in the short rest dialog, which Escape closes', async () => {
    server.use(http.get('/api/characters/:id', () => HttpResponse.json(hurtFighter)));
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('12/30')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Short Rest' }));
    const dialog = screen.getByRole('dialog', { name: 'Short Rest' });
    await user.click(within(dialog).getByRole('button', { name: 'Spend one more Fighter hit die' }));
    expect(within(dialog).getByRole('button', { name: 'Spend one fewer Fighter hit die' })).toBeEnabled();
    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Short Rest' })).toHaveFocus();
  });

  it('a short rest sends each Hit Die spent, with the roll entered or none for the server to roll', async () => {
    const multiclass = {
      ...hurtFighter, characterClass: 'Fighter / Warlock',
      hitDice: { Fighter: { total: 3, remaining: 2, dieSize: 10 }, Warlock: { total: 2, remaining: 2, dieSize: 8 } },
    };
    server.use(http.get('/api/characters/:id', () => HttpResponse.json(multiclass)));
    const bodies = recordCommand('post', 'short-rest', { ...multiclass, hpCurrent: 27 });
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('12/30')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Short Rest' }));
    const dialog = screen.getByRole('dialog', { name: 'Short Rest' });
    await user.click(within(dialog).getByRole('button', { name: 'Spend one more Fighter hit die' }));
    await user.click(within(dialog).getByRole('button', { name: 'Spend one more Fighter hit die' }));
    await user.click(within(dialog).getByRole('button', { name: 'Spend one more Warlock hit die' }));
    const rest = within(dialog).getByRole('button', { name: 'Short Rest' });

    // A d10 can't roll 11
    await user.type(within(dialog).getByLabelText('Fighter die 1 roll'), '11');
    expect(within(dialog).getByText('1 to 10')).toBeInTheDocument();
    expect(rest).toBeDisabled();
    await user.clear(within(dialog).getByLabelText('Fighter die 1 roll'));
    await user.type(within(dialog).getByLabelText('Fighter die 1 roll'), '7');
    await user.click(rest);

    await waitFor(() => expect(bodies).toEqual([{ hitDice: [
      { className: 'Fighter', roll: 7 }, { className: 'Fighter' }, { className: 'Warlock' },
    ] }]));
    expect(await screen.findByText('27/30')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Short rest taken: 15 hit points regained');
  });

  it('spending a spell slot sends the spend command for that slot', async () => {
    const wizard = {
      ...makeMockCharacter('char-001', 'Elminster', 'Wizard', 3), spellcastingAbility: 'INT',
      spellSlots: { '1': { max: 4, used: 1 }, '2': { max: 2, used: 0 } },
    };
    server.use(http.get('/api/characters/:id', () => HttpResponse.json(wizard)));
    recordCommand('post', 'spell-slots/:slot/spend', { ...wizard, spellSlots: { ...wizard.spellSlots, '1': { max: 4, used: 2 } } });
    renderSheet();
    const user = userEvent.setup();
    await user.click(await screen.findByRole('tab', { name: /Spells/ }));
    const requests = recordRequests();

    expect(screen.getByText('3/4')).toBeInTheDocument();
    await user.click(screen.getByText('3/4'));

    expect(await screen.findByText('2/4')).toBeInTheDocument();
    expect(requests).toEqual(['POST /api/characters/char-001/spell-slots/1/spend']);
  });

  it('damage, healing and temporary hit points go to the server, which applies the rules', async () => {
    server.use(http.get('/api/characters/:id', () => HttpResponse.json(hurtFighter)));
    const bodies = recordCommand('post', 'hp', { ...hurtFighter, hpCurrent: 7 });
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('12/30')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Damage' }));
    const dialog = screen.getByRole('dialog', { name: 'Take Damage' });
    await user.type(within(dialog).getByLabelText('Amount'), '5');
    await user.click(within(dialog).getByRole('button', { name: 'Take Damage' }));
    expect(await screen.findByText('7/30')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Heal' }));
    await user.type(within(screen.getByRole('dialog', { name: 'Heal' })).getByLabelText('Amount'), '3');
    await user.click(within(screen.getByRole('dialog', { name: 'Heal' })).getByRole('button', { name: 'Heal' }));
    await user.click(await screen.findByRole('button', { name: '+ Temp HP' }));
    const temporary = screen.getByRole('dialog', { name: 'Temporary Hit Points' });
    await user.type(within(temporary).getByLabelText('Amount'), '4');
    await user.click(within(temporary).getByRole('button', { name: 'Gain' }));

    await waitFor(() => expect(bodies).toEqual([
      { kind: 'DAMAGE', amount: 5 }, { kind: 'HEALING', amount: 3 }, { kind: 'TEMPORARY', amount: 4 },
    ]));
  });

  it('a pool\'s buttons spend and recover its uses through the server', async () => {
    const barbarian = {
      ...makeMockCharacter('char-001', 'Grog', 'Barbarian', 5),
      resourcePools: [makeMockResourcePool('class:barbarian-rage', 'Rage', { maxUses: 3, currentUses: 2, resetOn: 'longRest' })],
    };
    server.use(http.get('/api/characters/:id', () => HttpResponse.json(barbarian)));
    const spent = recordCommand('post', 'pools/spend', barbarian);
    const recovered = recordCommand('post', 'pools/recover', barbarian);
    renderSheet();
    const user = userEvent.setup();

    await user.click(await screen.findByRole('button', { name: 'Spend one Rage use' }));
    await user.click(screen.getByRole('button', { name: 'Recover one Rage use' }));

    await waitFor(() => expect(recovered).toHaveLength(1));
    expect(spent).toEqual([{ poolId: 'class:barbarian-rage' }]);
    expect(recovered).toEqual([{ poolId: 'class:barbarian-rage' }]);
  });

  it('resting and levelling wait while the character is in an encounter under way, and the sheet says why (D166)', async () => {
    server.use(http.get('/api/characters/:id', () => HttpResponse.json({ ...hurtFighter, inLiveEncounter: true })));
    renderSheet();

    expect(await screen.findByText('In an encounter that is under way: resting, levelling and choices wait until it ends.'))
      .toBeInTheDocument();
    for (const name of ['Short Rest', 'Long Rest', 'Level Up', 'Level Down']) {
      expect(screen.getByRole('button', { name })).toBeDisabled();
    }
    // Hit points can still change on the sheet
    expect(screen.getByRole('button', { name: 'Damage' })).toBeEnabled();
  });

  describe('Pending Choices (D159)', () => {
    const style = makeMockPendingChoice({
      id: 'choice-style', type: 'FIGHTING_STYLE', characterLevel: 6, classLevel: 6, options: ['Archery', 'Defense'],
    });
    const improvement = makeMockPendingChoice({ id: 'choice-asi', characterLevel: 6, classLevel: 6 });
    const older = makeMockPendingChoice({ id: 'choice-old', characterLevel: 4, classLevel: 4 });
    const levelled = { ...makeMockCharacter('char-001', 'Gimli', 'Fighter', 6), pendingChoices: [older, style, improvement] };

    async function levelUp(user: ReturnType<typeof userEvent.setup>) {
      await user.click(screen.getByTitle('Level Up'));
      const confirm = within(screen.getByRole('dialog', { name: 'Level Up' })).getByRole('button', { name: 'Level Up' });
      await waitFor(() => expect(confirm).toBeEnabled());
      await user.click(confirm);
    }

    it('lists each choice its levels owe on a banner, with a button that opens its dialog', async () => {
      server.use(http.get('/api/characters/:id', () => HttpResponse.json(levelled)));
      renderSheet();
      const user = userEvent.setup();

      const banner = await screen.findByRole('region', { name: 'Choices to make' });
      expect(within(banner).getByText('3 choices to make')).toBeInTheDocument();
      expect(within(banner).getAllByRole('button').map(b => b.textContent)).toEqual([
        'Ability Score Improvement (Fighter 4)', 'Fighting Style (Fighter 6)', 'Ability Score Improvement (Fighter 6)',
      ]);

      await user.click(within(banner).getByRole('button', { name: 'Fighting Style (Fighter 6)' }));

      expect(screen.getByRole('dialog', { name: 'Choose a Fighting Style' })).toBeInTheDocument();
    });

    it('waits while the character is in an encounter under way', async () => {
      server.use(http.get('/api/characters/:id', () => HttpResponse.json({ ...levelled, inLiveEncounter: true })));
      renderSheet();

      const banner = await screen.findByRole('region', { name: 'Choices to make' });
      within(banner).getAllByRole('button').forEach(button => expect(button).toBeDisabled());
    });

    it("opens the new level's choices one after another; one skipped stays on the banner", async () => {
      server.use(http.post('/api/characters/:id/level-up', () => HttpResponse.json({ character: levelled, newFeatures: [] })));
      renderSheet();
      const user = userEvent.setup();
      expect(await screen.findByText('Test Character')).toBeInTheDocument();

      await levelUp(user);
      // The level's own, in order: the level 4 improvement waits on the banner
      const first = await screen.findByRole('dialog', { name: 'Choose a Fighting Style' });
      await user.click(within(first).getByRole('button', { name: 'Skip' }));
      const second = await screen.findByRole('dialog', { name: 'Ability Score Improvement' });
      expect(within(second).getByText('Fighter 6 (level 6)')).toBeInTheDocument();
      await user.click(within(second).getByRole('button', { name: 'Skip' }));

      expect(await screen.findByText('Level up to 6 complete!')).toBeInTheDocument();
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(within(screen.getByRole('region', { name: 'Choices to make' })).getAllByRole('button')).toHaveLength(3);
    });

    it('makes a choice by its ID, shows the character the server returns, and opens the choices it brought', async () => {
      // The Eldritch Knight's cantrips its subclass brought
      const cantrips = makeMockPendingChoice({
        id: 'choice-cantrips', type: 'CANTRIPS', characterLevel: 6, classLevel: 3, count: 2, maxSpellLevel: 0,
      });
      const afterStyle = { ...levelled, pendingChoices: [older, cantrips, improvement] };
      const bodies = recordCommand('post', 'apply-choices', afterStyle);
      server.use(http.post('/api/characters/:id/level-up', () => HttpResponse.json({ character: levelled, newFeatures: [] })));
      renderSheet();
      const user = userEvent.setup();
      expect(await screen.findByText('Test Character')).toBeInTheDocument();

      await levelUp(user);
      const dialog = await screen.findByRole('dialog', { name: 'Choose a Fighting Style' });
      await user.click(within(dialog).getByRole('button', { name: /Defense/ }));
      await user.click(within(dialog).getByRole('button', { name: 'Confirm' }));

      expect(await screen.findByRole('dialog', { name: 'Learn Cantrips' })).toBeInTheDocument();
      expect(bodies).toEqual([{ choiceId: 'choice-style', fightingStyle: 'Defense' }]);
      const banner = screen.getByRole('region', { name: 'Choices to make' });
      expect(within(banner).queryByRole('button', { name: 'Fighting Style (Fighter 6)' })).not.toBeInTheDocument();
      expect(within(banner).getByRole('button', { name: 'Cantrips (Fighter 3)' })).toBeInTheDocument();
    });

    it('closing a dialog stops the run', async () => {
      server.use(http.post('/api/characters/:id/level-up', () => HttpResponse.json({ character: levelled, newFeatures: [] })));
      renderSheet();
      const user = userEvent.setup();
      expect(await screen.findByText('Test Character')).toBeInTheDocument();

      await levelUp(user);
      await screen.findByRole('dialog', { name: 'Choose a Fighting Style' });
      await user.keyboard('{Escape}');

      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(await screen.findByText('Level up to 6 complete!')).toBeInTheDocument();
    });
  });

  it('shows the server\'s message when it refuses a command', async () => {
    server.use(
      http.get('/api/characters/:id', () => HttpResponse.json({ ...hurtFighter, hpCurrent: 0 })),
      http.post('/api/characters/:id/long-rest', () => HttpResponse.json(
        { error: 'Gimli needs at least 1 hit point to benefit from a long rest' }, { status: 409 })),
    );
    renderSheet();
    const user = userEvent.setup();
    expect(await screen.findByText('0/30')).toBeInTheDocument();

    await user.click(screen.getByTitle('Long Rest'));
    await user.click(within(screen.getByRole('dialog')).getByRole('button', { name: 'Long Rest' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Gimli needs at least 1 hit point to benefit from a long rest');
  });

  it('shows the server\'s message when the character cannot be loaded', async () => {
    server.use(
      http.get('/api/characters/:id', () =>
        HttpResponse.json({ error: 'You do not have access to this character' }, { status: 403 })),
    );
    renderSheet();

    expect(await screen.findByRole('alert')).toHaveTextContent('You do not have access to this character');
    expect(screen.queryByText('Loading character...')).not.toBeInTheDocument();
  });
});
