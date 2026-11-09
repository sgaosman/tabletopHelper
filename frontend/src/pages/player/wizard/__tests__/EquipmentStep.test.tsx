import { describe, it, expect } from 'vitest';
import { useReducer } from 'react';
import userEvent from '@testing-library/user-event';
import { render, screen } from '../../../../test/test-utils';
import type { StartingEquipment } from '../../../../types/reference';
import EquipmentStep from '../EquipmentStep';
import { initialWizardState, wizardReducer, type WizardState } from '../wizardReducer';
import { fighter, human, soldier } from './fixtures';

const FIGHTER_KIT: StartingEquipment = {
  groups: [{ choice: true, options: [
    { key: 'a', entries: [{ kind: 'ITEM', name: 'Chain Mail', quantity: 1, itemId: 'chain-mail', armourCategory: 'HEAVY', coinsCp: 0, candidates: [] }] },
    { key: 'b', entries: [{ kind: 'ITEM', name: 'Leather Armor', quantity: 1, itemId: 'leather', armourCategory: 'LIGHT', coinsCp: 0, candidates: [] }] },
  ] }],
  goldAlternative: { count: 5, sides: 4, multiplier: 10 },
};

const START: WizardState = {
  ...initialWizardState, name: 'Brienne', selectedRace: human, selectedClass: fighter, selectedBackground: soldier,
  classEntries: [{ cls: fighter, level: 1, subclass: null, subclasses: [] }],
};

function Harness() {
  const [state, dispatch] = useReducer(wizardReducer, START);
  return <EquipmentStep state={state} dispatch={dispatch} failed={false}
    kits={{ cls: FIGHTER_KIT, background: { groups: [], goldAlternative: null } }} />;
}

describe('EquipmentStep', () => {
  it('offers the class\'s options and the server\'s AC preview', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(screen.getByRole('radio', { name: '(a) Chain Mail' }));

    expect(screen.getByRole('radio', { name: '(a) Chain Mail' })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByText('Carrying: Chain Mail (worn)')).toBeInTheDocument();
    // The mock server's preview: 10 + DEX 2
    expect(await screen.findByText('Armour Class 12')).toBeInTheDocument();
    expect(screen.getByText('No starting equipment listed.')).toBeInTheDocument();
  });

  it('takes the starting wealth instead, in place of the equipment', async () => {
    const user = userEvent.setup();
    render(<Harness />);

    await user.click(screen.getByRole('radio', { name: 'Take 5d4 × 10 gp instead' }));

    expect(screen.getByLabelText('Gold pieces (50 to 200)')).toHaveValue(50);
    expect(screen.queryByRole('radio', { name: '(a) Chain Mail' })).not.toBeInTheDocument();
    await user.click(screen.getByRole('radio', { name: 'Take the equipment' }));
    expect(screen.getByRole('radio', { name: '(a) Chain Mail' })).toBeInTheDocument();
  });
});
