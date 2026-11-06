import { describe, it, expect, vi } from 'vitest';
import { render as plainRender, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import { makeMockCondition, makeMockParticipant } from '../../test/mocks/handlers';
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
});
