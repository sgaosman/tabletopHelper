import { describe, it, expect } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, renderWithGlossary, screen, within } from '../../test/test-utils';
import { makeMockMonster } from '../../test/mocks/handlers';
import MonsterStatBlock from './MonsterStatBlock';

const wolf = {
  ...makeMockMonster('m1', 'Wolf', 'beast', '1/4', 11, 13),
  senses: { special: ['darkvision 60 ft.'], passive_perception: 13 },
  actions: [{
    name: 'Bite',
    description: 'Melee Weapon Attack: +4 to hit. If the target is a creature, it must succeed on a DC 11 Strength saving throw or be knocked prone.',
  }],
};

describe('MonsterStatBlock', () => {
  it('shows an action\'s text as plain text without the Glossary', () => {
    render(<MonsterStatBlock monster={wolf} />);
    expect(screen.getByText(/or be knocked prone\./)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('links conditions in actions and senses to the Glossary (M19)', async () => {
    const user = userEvent.setup();
    renderWithGlossary(<MonsterStatBlock monster={wolf} />);

    expect(await screen.findByRole('button', { name: 'darkvision' })).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'prone' }));
    expect(screen.getByRole('dialog', { name: 'Prone' })).toBeInTheDocument();
  });

  it('converts the 5etools tags left in monster text: an italic "Hit:" and the recharge (M19)', async () => {
    const ogre = {
      ...makeMockMonster('m2', 'Ogre', 'giant', '2', 59, 11),
      actions: [{ name: 'Greatclub', description: '{@atk mw} {@hit 6} to hit, reach 5 ft., one target. {@h}12 (2d8 + 3) bludgeoning damage.' }],
      reactions: [{ name: 'Roar {@recharge 5}', description: 'Each creature must succeed on a {@dc 13} save or be {@condition frightened}.' }],
    };
    for (const renderIt of [render, renderWithGlossary]) {
      const { unmount } = renderIt(<MonsterStatBlock monster={ogre} />);
      const hit = await screen.findByText('Hit:');
      expect(hit.tagName).toBe('EM');
      expect(hit.parentElement).toHaveTextContent('Melee Weapon Attack: +6 to hit, reach 5 ft., one target. Hit: 12 (2d8 + 3) bludgeoning damage.');
      expect(screen.getByText('Roar (Recharge 5-6).')).toBeInTheDocument();
      const reaction = screen.getByText(/Each creature must succeed/);
      expect(reaction).toHaveTextContent('Each creature must succeed on a DC 13 save or be frightened.');
      expect(screen.queryByText(/\{@/)).not.toBeInTheDocument();
      if (renderIt === renderWithGlossary) {
        expect(await within(reaction).findByRole('button', { name: 'frightened' })).toBeInTheDocument();
      }
      unmount();
    }
  });
});

