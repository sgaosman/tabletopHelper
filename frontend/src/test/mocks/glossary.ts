import type { GlossaryEntry } from '../../types/reference';

/** A few Glossary entries (M19), shaped as GET /api/reference/glossary sends them. */
export const GLOSSARY = [
  {
    id: 'prone', name: 'Prone', category: 'CONDITION', aliases: [],
    summary: 'You are on the ground and can only crawl until you stand up.',
    body: 'A prone creature\'s only movement option is to crawl.\nThe creature has disadvantage on attack rolls.',
    source: 'PHB p.292', seeAlso: ['advantage'],
  },
  {
    id: 'frightened', name: 'Frightened', category: 'CONDITION', aliases: [],
    summary: 'You have disadvantage on ability checks and attack rolls while the source of your fear is in sight.',
    body: 'A frightened creature has disadvantage on ability checks and attack rolls.', source: 'PHB p.290', seeAlso: [],
  },
  {
    id: 'advantage', name: 'Advantage and Disadvantage', category: 'RULE', aliases: ['advantage', 'disadvantage'],
    summary: 'With advantage, roll two d20s and use the higher; with disadvantage, use the lower.',
    body: 'Sometimes a special ability or spell tells you that you have advantage or disadvantage.',
    source: 'PHB p.173', seeAlso: ['prone'],
  },
  {
    id: 'spell-save-dc', name: 'Spell Save DC', category: 'SPELLCASTING', aliases: [],
    summary: 'The number a target\'s saving throw must equal or beat to resist your spell.', body: null,
    source: 'PHB p.205', seeAlso: [],
  },
  {
    id: 'difficulty-class', name: 'Difficulty Class', category: 'RULE', aliases: ['dc'],
    summary: 'The number an ability check or saving throw has to equal or beat to succeed.', body: null,
    source: 'PHB p.174', seeAlso: [],
  },
  {
    id: 'saving-throw', name: 'Saving Throw', category: 'RULE', aliases: ['saving throws'],
    summary: 'A d20 roll to resist a spell, trap or other danger.', body: null, source: 'PHB p.179', seeAlso: [],
  },
  {
    id: 'action', name: 'Action', category: 'COMBAT', aliases: [],
    summary: 'The main thing you do on your turn.', body: null, source: 'PHB p.192', seeAlso: [],
  },
  {
    id: 'dash', name: 'Dash', category: 'ACTION', aliases: [],
    summary: 'Gain extra movement equal to your speed for this turn.', body: null, source: 'PHB p.192', seeAlso: [],
  },
  {
    id: 'fire', name: 'Fire', category: 'DAMAGE_TYPE', aliases: [], summary: null,
    body: 'Red dragons breathe fire, and many spells conjure flames to deal fire damage.', source: 'PHB p.196',
    seeAlso: [],
  },
  {
    id: 'cold', name: 'Cold', category: 'DAMAGE_TYPE', aliases: [], summary: null,
    body: 'The infernal chill radiating from an ice devil\'s spear deals cold damage.', source: 'PHB p.196',
    seeAlso: [],
  },
  {
    id: 'perception', name: 'Perception', category: 'SKILL', aliases: [],
    summary: 'A Wisdom skill.', body: 'Your Wisdom (Perception) check lets you spot something.', source: 'PHB p.178',
    seeAlso: [],
  },
  {
    id: 'darkvision', name: 'Darkvision', category: 'SENSE', aliases: [],
    summary: 'You see in the dark as if it were dim light.', body: 'Many creatures have darkvision.',
    source: 'PHB p.183', seeAlso: [],
  },
] satisfies GlossaryEntry[];
