import { describe, it, expect, vi } from 'vitest';
import { render as plainRender, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render, renderWithGlossary, setupAuthToken } from '../../test/test-utils';
import { http, HttpResponse } from 'msw';
import { makeMockCondition, makeMockEncounter, makeMockParticipant } from '../../test/mocks/handlers';
import { server } from '../../test/mocks/server';
import { EncounterProvider } from '../../context/EncounterContext';
import ConditionChips from './ConditionChips';

const restrained = makeMockCondition('restrained', { sourceSpellName: 'Entangle', duration: 10, appliedRound: 2 });
const paralyzed = makeMockCondition('paralyzed', { repeatSave: { ability: 'CON', dc: 13, awaitingRoll: true } });
const goblin = makeMockParticipant('p', 'Test Goblin', 'MONSTER', { activeConditions: [restrained, paralyzed] });

describe('ConditionChips', () => {
  it('labels each condition with its source spell and the rounds it has left', () => {
    plainRender(<ConditionChips participant={goblin} roundNumber={5} />);
    expect(screen.getByText('restrained (Entangle) (7)')).toHaveAttribute('title', '7 rounds left');
    expect(screen.getByText('paralyzed')).toHaveAttribute('title', '; CON save DC 13 at the end of each turn ends it');
  });

  it('without onRemove or showRepeatSave offers no controls, as for another player\'s character', () => {
    plainRender(<ConditionChips participant={goblin} roundNumber={5} />);
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('removes a condition with onRemove', async () => {
    const onRemove = vi.fn();
    plainRender(<ConditionChips participant={goblin} roundNumber={5} onRemove={onRemove} />);

    await userEvent.setup().click(screen.getByRole('button', { name: 'paralyzed' }));
    expect(onRemove).toHaveBeenCalledWith(paralyzed);
  });

  it('shows exhaustion as one chip with its level and what it does, even with no other condition (D183)', () => {
    const tired = makeMockParticipant('p', 'Gimli', 'PLAYER', { exhaustionLevel: 2 });
    plainRender(<ConditionChips participant={tired} roundNumber={1} />);
    expect(screen.getByText('Exhausted (2)'))
      .toHaveAttribute('title', 'Disadvantage on ability checks; Speed halved');
  });

  it('keeps exhaustion out of reach of onRemove: the DM changes it with the exhaustion control', () => {
    const onRemove = vi.fn();
    const tired = makeMockParticipant('p', 'Gimli', 'PLAYER', { exhaustionLevel: 1, activeConditions: [restrained] });
    plainRender(<ConditionChips participant={tired} roundNumber={2} onRemove={onRemove} />);
    expect(screen.getAllByRole('button')).toHaveLength(1);
    expect(screen.getByText('Exhausted (1)')).toBeInTheDocument();
  });

  it('shows nothing without conditions', () => {
    const { container } = plainRender(<ConditionChips participant={makeMockParticipant('p', 'Gimli', 'PLAYER')} roundNumber={1} />);
    expect(container).toBeEmptyDOMElement();
  });

  it('with showRepeatSave adds the DM\'s roll beside a Repeat Save that waits on a player (D162)', async () => {
    setupAuthToken();
    render(
      <EncounterProvider encounterId="enc-001">
        <ConditionChips participant={goblin} roundNumber={5} showRepeatSave />
      </EncounterProvider>,
    );
    await waitFor(() => expect(screen.getByRole('button', { name: 'Roll CON save' })).toBeInTheDocument());
    expect(screen.getAllByRole('button')).toHaveLength(1);
  });

  it('offers a save made with an action on any turn, as Otto\'s Irresistible Dance\'s, and sends it (D188)', async () => {
    setupAuthToken();
    let sent: unknown = null;
    server.use(http.post('/api/encounters/:id/combat/action-save', async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(makeMockEncounter());
    }));
    const dancing = makeMockCondition('dancing', {
      sourceSpellName: "Otto's Irresistible Dance",
      repeatSave: { ability: 'WIS', dc: 15, awaitingRoll: false, byAction: true },
    });
    const dancer = makeMockParticipant('p', 'Ogre', 'MONSTER', { activeConditions: [dancing] });
    render(
      <EncounterProvider encounterId="enc-001">
        <ConditionChips participant={dancer} roundNumber={1} showRepeatSave />
      </EncounterProvider>,
    );
    expect(screen.getByText("dancing (Otto's Irresistible Dance)"))
      .toHaveAttribute('title', '; a WIS save DC 15 made with its action ends the spell');
    await userEvent.setup().click(await screen.findByRole('button', { name: 'Action: WIS save' }));
    await waitFor(() => expect(sent).toEqual({ participantId: 'p', conditionId: 'cond-dancing' }));
  });

  it("names the ability check an action is spent on, as Wrathful Smite's and Phantasmal Force's (D200)", async () => {
    setupAuthToken();
    let sent: unknown = null;
    server.use(http.post('/api/encounters/:id/combat/action-save', async ({ request }) => {
      sent = await request.json();
      return HttpResponse.json(makeMockEncounter());
    }));
    const frightened = makeMockCondition('frightened', {
      sourceSpellName: 'Wrathful Smite',
      repeatSave: { ability: 'WIS', dc: 13, awaitingRoll: false, byAction: true, check: true },
    });
    const phantasm = makeMockCondition('phantasm', {
      sourceSpellName: 'Phantasmal Force',
      repeatSave: { ability: 'INT', dc: 14, awaitingRoll: false, byAction: true, check: true, skill: 'Investigation' },
    });
    const ogre = makeMockParticipant('p', 'Ogre', 'MONSTER', { activeConditions: [frightened, phantasm] });
    render(
      <EncounterProvider encounterId="enc-001">
        <ConditionChips participant={ogre} roundNumber={1} showRepeatSave />
      </EncounterProvider>,
    );
    expect(screen.getByText('frightened (Wrathful Smite)'))
      .toHaveAttribute('title', '; a WIS check DC 13 made with its action ends the spell');
    expect(await screen.findByRole('button', { name: 'Use action: INT (Investigation) check' })).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /save/ })).not.toBeInTheDocument();
    await userEvent.setup().click(screen.getByRole('button', { name: 'Use action: WIS check' }));
    await waitFor(() => expect(sent).toEqual({ participantId: 'p', conditionId: 'cond-frightened' }));
  });

  it("shows Rage and Reckless Attack as the creature's own features, with what they do (M17)", () => {
    const grog = makeMockParticipant('p', 'Grog', 'PLAYER', { activeConditions: [
      makeMockCondition('raging', { duration: 10, appliedRound: 2, sourceSpellName: 'Rage' }),
      makeMockCondition('reckless', { sourceSpellName: 'Reckless Attack' }),
    ] });
    plainRender(<ConditionChips participant={grog} roundNumber={4} />);

    expect(screen.getByText('Raging (8)')).toHaveAttribute('title', expect.stringContaining('resistance to bludgeoning'));
    expect(screen.getByText('Reckless').getAttribute('title')).toContain('attacks against it have advantage');
  });

  it("shows a Kensei monk's Agile Parry, Kensei's Shot and Sharpen the Blade as its own features (XGE p.34-35)", () => {
    const ren = makeMockParticipant('p', 'Ren', 'PLAYER', { activeConditions: [
      makeMockCondition('agile parry', { sourceSpellName: 'Agile Parry', bonus: 2, weapon: 'Longsword' }),
      makeMockCondition("kensei's shot", { sourceSpellName: "Kensei's Shot" }),
      makeMockCondition('sharpen the blade', { sourceSpellName: 'Sharpen the Blade', bonus: 2, weapon: 'Longbow',
        duration: 10, appliedRound: 2 }),
    ] });
    plainRender(<ConditionChips participant={ren} roundNumber={4} />);

    expect(screen.getByText('Agile Parry +2 AC').getAttribute('title'))
      .toContain("+2 AC until the start of its next turn, while it isn't incapacitated");
    expect(screen.getByText("Kensei's Shot").getAttribute('title')).toContain('extra 1d4 damage');
    expect(screen.getByText('Sharpen the Blade: Longbow +2 (8)')).toBeInTheDocument();
  });

  it('with the Glossary, a read-only chip the Glossary defines opens its definition (M19)', async () => {
    const user = userEvent.setup();
    const prone = makeMockCondition('Prone');
    renderWithGlossary(<ConditionChips participant={makeMockParticipant('p', 'Gimli', 'PLAYER', { activeConditions: [prone, restrained] })} roundNumber={2} />);

    await user.click(await screen.findByRole('button', { name: 'Prone' }));

    expect(screen.getByRole('dialog', { name: 'Prone' })).toBeInTheDocument();
    // The mock Glossary doesn't define Restrained, so its chip stays plain
    expect(screen.queryByRole('button', { name: /restrained/ })).not.toBeInTheDocument();
  });

  it('with the Glossary, a removable chip still removes its condition', async () => {
    const onRemove = vi.fn();
    const prone = makeMockCondition('Prone');
    renderWithGlossary(<ConditionChips participant={makeMockParticipant('p', 'Gimli', 'PLAYER', { activeConditions: [prone] })} roundNumber={2} onRemove={onRemove} />);

    await waitFor(() => expect(screen.getByRole('button', { name: 'Prone' })).toBeInTheDocument());
    await userEvent.setup().click(screen.getByRole('button', { name: 'Prone' }));
    expect(onRemove).toHaveBeenCalledWith(prone);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
});

