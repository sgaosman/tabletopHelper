import type { EncounterView } from '../types/encounter';
import type { MonsterSearchParams } from '../types/monster';
import type { ItemSearchParams, SpellSearchParams } from '../types/reference';

/**
 * Query keys for every domain (D171). Keys nest: invalidating one also invalidates every key that starts
 * with it, so ['campaigns', id] covers that campaign's characters and encounters. Pass `exact: true` to
 * touch only the key itself, such as the campaign list without each campaign.
 */
export const queryKeys = {
  campaigns: {
    /** The campaigns the user runs or plays in. */
    list: () => ['campaigns'] as const,
    detail: (campaignId: string) => ['campaigns', campaignId] as const,
    characters: (campaignId: string) => ['campaigns', campaignId, 'characters'] as const,
    encounters: (campaignId: string) => ['campaigns', campaignId, 'encounters'] as const,
  },
  characters: {
    /** The user's own characters. */
    mine: () => ['characters'] as const,
    detail: (characterId: string) => ['characters', characterId] as const,
    eligibleClasses: (characterId: string) => ['characters', characterId, 'eligible-classes'] as const,
    featOptions: (characterId: string) => ['characters', characterId, 'feat-options'] as const,
    /** The armour class a creation request would give, for the wizard (nothing is saved). */
    armourClassPreview: (request: unknown) => ['characters', 'armour-class-preview', request] as const,
  },
  encounters: {
    /** Everything cached for one encounter, in both views. */
    views: (encounterId: string) => ['encounters', encounterId] as const,
    /** A live encounter as the DM View or the Player View shows it (D157). */
    detail: (encounterId: string, view: EncounterView) => ['encounters', encounterId, view] as const,
    /** The combat log of that view. */
    log: (encounterId: string, view: EncounterView) => ['encounters', encounterId, view, 'log'] as const,
    /** What ending the encounter would write back to each character sheet, which only the DM may read (D166). */
    results: (encounterId: string) => ['encounters', encounterId, 'dm', 'results'] as const,
    /** A monster's action templates and current resource pools, which only the DM may read (D157). */
    monsterActions: (encounterId: string, participantId: string) =>
      ['encounters', encounterId, 'dm', 'participants', participantId, 'actions'] as const,
    /** What a participant can cast now and what pays for each spell: the DM's for anyone, a player's for their own. */
    spellOptions: (encounterId: string, view: EncounterView, participantId: string) =>
      ['encounters', encounterId, view, 'participants', participantId, 'spell-options'] as const,
    /** The weapons a participant can attack with and their numbers: the DM's for anyone, a player's for their own. */
    attackOptions: (encounterId: string, view: EncounterView, participantId: string) =>
      ['encounters', encounterId, view, 'participants', participantId, 'attack-options'] as const,
    /**
     * What the attacker's and the target's conditions do to an attack roll between them; `conditions` names both
     * sides' conditions, so the preview loads again when they change.
     */
    attackRollPreview: (encounterId: string, view: EncounterView, attackerId: string | null, targetId: string,
      withinFiveFeet: boolean, conditions: string) =>
      ['encounters', encounterId, view, 'attack-roll-preview', attackerId ?? 'dm', targetId, withinFiveFeet,
        conditions] as const,
    /** A monster's multiattacks and the attacks each makes, which only the DM may read (D157, D165). */
    multiattackOptions: (encounterId: string, participantId: string) =>
      ['encounters', encounterId, 'dm', 'participants', participantId, 'multiattack-options'] as const,
    /** The beasts a druid can become with Wild Shape now: the DM's for anyone, a player's for their own (PHB p.66). */
    wildShapeOptions: (encounterId: string, view: EncounterView, participantId: string) =>
      ['encounters', encounterId, view, 'participants', participantId, 'wild-shape-options'] as const,
    /** The creatures a form spell can turn a target into (D192): the DM's for anyone, a player's for their own caster. */
    formOptions: (encounterId: string, view: EncounterView, participantId: string, spell: string, targetId: string) =>
      ['encounters', encounterId, view, 'participants', participantId, 'form-options', spell, targetId] as const,
    /**
     * What a summoning spell can bring when the participant casts it at a slot level (D182): the DM's for anyone, a
     * player's for their own. The builder asks too, outside a live view, so the key has no view.
     */
    summonOptions: (encounterId: string, participantId: string, spellName: string, slotLevel: number | null) =>
      ['encounters', encounterId, 'participants', participantId, 'summon-options', spellName, slotLevel] as const,
  },
  monsters: {
    all: () => ['monsters'] as const,
    search: (name: string, maxResults: number) => ['monsters', 'search', name, maxResults] as const,
    list: (params: MonsterSearchParams) => ['monsters', 'list', params] as const,
    detail: (monsterId: string) => ['monsters', 'detail', monsterId] as const,
    filterOptions: (filter: 'types' | 'challenge-ratings' | 'sources') => ['monsters', 'filters', filter] as const,
  },
  reference: {
    all: () => ['reference'] as const,
    spells: (params: SpellSearchParams) => ['reference', 'spells', 'list', params] as const,
    spell: (spellId: string) => ['reference', 'spells', 'detail', spellId] as const,
    spellByName: (name: string) => ['reference', 'spells', 'byName', name] as const,
    spellFilterOptions: (filter: 'schools' | 'sources' | 'classes') => ['reference', 'spells', 'filters', filter] as const,
    spellSubclasses: (className: string) => ['reference', 'spells', 'filters', 'subclasses', className] as const,
    spellTargeting: (spellName: string, slotLevel: number) =>
      ['reference', 'spells', 'targeting', spellName, slotLevel] as const,
    items: (params: ItemSearchParams) => ['reference', 'items', 'list', params] as const,
    item: (itemId: string) => ['reference', 'items', 'detail', itemId] as const,
    itemFilterOptions: (filter: 'types' | 'rarities' | 'sources') => ['reference', 'items', 'filters', filter] as const,
    conditions: () => ['reference', 'conditions'] as const,
    quickReference: () => ['reference', 'quick-reference'] as const,
    classTables: () => ['reference', 'class-tables'] as const,
    glossary: () => ['reference', 'glossary'] as const,
    races: (source?: string) => ['reference', 'races', 'list', source ?? 'all'] as const,
    raceSources: () => ['reference', 'races', 'filters', 'sources'] as const,
    classes: () => ['reference', 'classes'] as const,
    subclasses: (classId: string) => ['reference', 'classes', classId, 'subclasses'] as const,
    backgrounds: () => ['reference', 'backgrounds'] as const,
    startingEquipment: (owner: 'classes' | 'backgrounds', id: string) =>
      ['reference', owner, id, 'starting-equipment'] as const,
    feats: () => ['reference', 'feats'] as const,
    optionalFeatures: (type?: string) => ['reference', 'optional-features', type ?? 'all'] as const,
  },
};
