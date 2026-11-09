import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockParticipant, makeMockSpellEffect } from '../../test/mocks/handlers';
import SummonAwaitingBanner from './SummonAwaitingBanner';

const demons = makeMockSpellEffect('Summon Lesser Demons', {
  id: 'effect-demons', slotLevel: 3, summon: { optionId: '8xCR1/4', multiplier: 1, roll: 6, status: 'AWAITING_DM' },
});
const placed = makeMockSpellEffect('Conjure Animals', {
  summon: { optionId: '1xCR2', multiplier: 1, roll: null, status: 'PLACED' },
});

describe('SummonAwaitingBanner', () => {
  it('shows each summoning waiting for the DM, with Summon Lesser Demons\' d6, and opens the picker', async () => {
    const onChoose = vi.fn();
    const user = userEvent.setup();
    const warlock = makeMockParticipant('part-warlock', 'Hexblade', 'PLAYER', { activeEffects: [demons] });
    const druid = makeMockParticipant('part-druid', 'Radagast', 'PLAYER', { activeEffects: [placed] });
    render(<SummonAwaitingBanner participants={[warlock, druid]} onChoose={onChoose} />);

    const banner = screen.getByRole('status', { name: 'Summonings waiting for you' });
    expect(banner).toHaveTextContent(
      "Hexblade's Summon Lesser Demons waits for you to choose its creatures — rolled d6: 6, 8 of CR 1/4 or lower");
    expect(banner).not.toHaveTextContent('Conjure Animals');

    await user.click(screen.getByRole('button', { name: 'Choose creatures' }));
    expect(onChoose).toHaveBeenCalledWith(warlock, demons);
  });

  it('shows nothing while no summoning waits', () => {
    const { container } = render(
      <SummonAwaitingBanner participants={[makeMockParticipant('p', 'Radagast', 'PLAYER', { activeEffects: [placed] })]}
        onChoose={() => {}} />,
    );
    expect(container).toBeEmptyDOMElement();
  });
});
