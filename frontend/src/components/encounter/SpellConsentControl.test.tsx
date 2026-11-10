import { describe, it, expect, vi } from 'vitest';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render } from '../../test/test-utils';
import { makeMockParticipant } from '../../test/mocks/handlers';
import SpellConsentControl from './SpellConsentControl';
import ConsentChoices from './spellflow/ConsentChoices';
import { consentTargets } from '../../utils/spellConsent';

const rakshasa = makeMockParticipant('p-rak', 'Rakshasa', 'MONSTER', {
  spellConsentTrait: 'Limited Magic Immunity', spellConsent: 'HELPFUL',
});
const ogre = makeMockParticipant('p-ogre', 'Ogre', 'MONSTER');

describe('SpellConsentControl', () => {
  it('shows the trait and its standing choice, and sends a new one (D198)', async () => {
    const onChange = vi.fn().mockResolvedValue(undefined);
    const user = userEvent.setup();
    render(<SpellConsentControl participant={rakshasa} onChange={onChange} />);

    const select = screen.getByLabelText('Limited Magic Immunity: lets through');
    expect(select).toHaveValue('HELPFUL');
    await user.selectOptions(select, 'NEVER');
    expect(onChange).toHaveBeenCalledWith('NEVER');
  });

  it('shows nothing for a creature without such a trait', () => {
    const { container } = render(<SpellConsentControl participant={ogre} onChange={vi.fn()} />);
    expect(container).toBeEmptyDOMElement();
  });
});

describe('ConsentChoices', () => {
  it("starts on the creature's setting and records a choice for the cast, or clears it", async () => {
    const onChange = vi.fn();
    const user = userEvent.setup();
    const { rerender } = render(<ConsentChoices targets={[rakshasa]} spellName="Fireball" chosen={{}}
      onChange={onChange} />);

    expect(screen.getByText("Rakshasa's Limited Magic Immunity: does Fireball affect it?")).toBeInTheDocument();
    expect(screen.getByLabelText('Its setting (Helpful spells only)')).toBeChecked();
    await user.click(screen.getByLabelText('Yes, it wishes to be affected'));
    expect(onChange).toHaveBeenLastCalledWith({ 'p-rak': true });

    rerender(<ConsentChoices targets={[rakshasa]} spellName="Fireball" chosen={{ 'p-rak': true }}
      onChange={onChange} />);
    await user.click(screen.getByLabelText('Its setting (Helpful spells only)'));
    expect(onChange).toHaveBeenLastCalledWith({});
  });

  it('offers the choice to the DM for any such target, and to a player only for one they control', () => {
    const dominated = makeMockParticipant('p-dom', 'Dominated Rakshasa', 'MONSTER', {
      spellConsentTrait: 'Limited Magic Immunity', spellConsent: 'ALWAYS', controlledByUserId: 'user-1',
    });
    expect(consentTargets([rakshasa, ogre, dominated], undefined, true).map(t => t.id)).toEqual(['p-rak', 'p-dom']);
    expect(consentTargets([rakshasa, ogre, dominated], 'user-1', false).map(t => t.id)).toEqual(['p-dom']);
    expect(consentTargets([rakshasa, dominated], 'user-2', false)).toEqual([]);
  });
});
