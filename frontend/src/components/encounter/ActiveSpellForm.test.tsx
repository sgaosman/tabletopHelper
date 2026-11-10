import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { makeMockParticipant } from '../../test/mocks/handlers';
import ActiveSpellForm from './ActiveSpellForm';

const aria = makeMockParticipant('part-aria', 'Aria', 'PLAYER');
const bron = makeMockParticipant('part-bron', 'Bron', 'PLAYER');

describe('ActiveSpellForm', () => {
  it('sends the spell already going with its slot and creatures, then empties (D184)', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<ActiveSpellForm caster={aria} participants={[aria, bron]} onSubmit={onSubmit} />);

    await user.type(screen.getByLabelText('Spell already active'), 'Bless');
    await user.type(screen.getByLabelText('Slot'), '2');
    await user.click(screen.getByLabelText('Aria'));
    await user.click(screen.getByLabelText('Bron'));
    await user.click(screen.getByRole('button', { name: 'Start' }));

    expect(onSubmit).toHaveBeenCalledWith({
      casterId: 'part-aria', spellName: 'Bless', slotLevel: 2, option: undefined,
      targetIds: ['part-aria', 'part-bron'],
    });
    expect(screen.getByLabelText('Spell already active')).toHaveValue('');
  });

  it('sends a spell on its caster with no creatures, and needs a name first', async () => {
    const onSubmit = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<ActiveSpellForm caster={aria} participants={[aria]} onSubmit={onSubmit} />);

    expect(screen.getByRole('button', { name: 'Start' })).toBeDisabled();
    await user.type(screen.getByLabelText('Spell already active'), 'Mage Armor');
    await user.click(screen.getByRole('button', { name: 'Start' }));
    expect(onSubmit).toHaveBeenCalledWith(expect.objectContaining({ spellName: 'Mage Armor', targetIds: [] }));
  });
});
