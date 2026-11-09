import type {
  EncounterParticipant, SummonCandidate, SummonChoice, SummonInitiativeMode, SummonOption, SummonOptions,
} from '../types/encounter';

/**
 * The summoning spells the server places creatures for (D182), as SummonRules names them. The spell list doesn't say
 * which spells summon, so the cast flow asks for summon options only for these.
 */
export const SUMMONING_SPELLS = [
  'Conjure Animals', 'Summon Lesser Demons', 'Summon Beast', 'Summon Fey', 'Summon Shadowspawn', 'Summon Undead',
  'Find Familiar', 'Flock of Familiars', 'Find Steed', 'Animate Dead', 'Tiny Servant',
] as const;

/**
 * The summoning spells that take a minute or more to cast (PHB p.202), which the DM adds as companions before or
 * during the fight with no slot spent: they come first in the Add companion form.
 */
export const COMPANION_SPELLS = [
  'Find Familiar', 'Find Steed', 'Animate Dead', 'Tiny Servant', 'Flock of Familiars',
] as const;

const SUMMONING = new Set(SUMMONING_SPELLS.map(name => name.toLowerCase()));

/** Whether the spell summons creatures the server places (D182). */
export function isSummoningSpell(spellName: string | null | undefined): boolean {
  return !!spellName && SUMMONING.has(spellName.trim().toLowerCase());
}

/** How each initiative mode reads in the summon step. */
export const INITIATIVE_MODE_LABELS: Record<SummonInitiativeMode, string> = {
  FOLLOWS_CASTER: 'Acts right after the caster',
  GROUP: 'One initiative roll for the group',
  INDIVIDUAL: 'Each rolls its own initiative',
};

/** The choices being made in the summon step, before they become a request. */
export interface SummonDraft {
  optionId: string | null;
  /** How many of each creature, by monster ID */
  counts: Record<string, number>;
  variant: string | null;
  initiativeMode: SummonInitiativeMode | null;
  creatureType: string | null;
}

/**
 * The step's starting choices: the only option when there is one, the rules' initiative mode, the first spirit
 * variant, and a lone creature (a tiny servant) already counted up to the option's number. The DM placing a summoning
 * that waits starts on the option the casting chose or rolled.
 */
export function initialDraft(options: SummonOptions, optionId?: string | null): SummonDraft {
  const chosen = optionId && options.options.some(o => o.id === optionId) ? optionId
    : options.options.length === 1 ? options.options[0].id : null;
  const option = options.options.find(o => o.id === chosen);
  const lone = options.kind !== 'SPIRIT' && options.candidates.length === 1 && option ? options.candidates[0] : null;
  return {
    optionId: chosen,
    counts: lone && option ? { [lone.monsterId]: option.count } : {},
    variant: options.variants[0] ?? null,
    initiativeMode: options.defaultInitiativeMode,
    creatureType: options.creatureTypes.length === 1 ? options.creatureTypes[0] : null,
  };
}

/** Whether Animate Dead or Tiny Servant, which may bring fewer than the option's count (PHB p.212, XGE p.168). */
export function fewerAllowed(options: SummonOptions): boolean {
  return options.kind === 'ANIMATE_DEAD' || options.kind === 'TINY_SERVANT';
}

/** The creatures that fit the option: within its challenge rating, or all when it sets none. */
export function candidatesFor(options: SummonOptions, option: SummonOption | undefined): SummonCandidate[] {
  if (!option || option.maxCr == null) return options.candidates;
  return options.candidates.filter(c => c.challengeRatingValue == null || c.challengeRatingValue <= option.maxCr!);
}

/** The creatures counted so far. */
export function totalCount(draft: SummonDraft): number {
  return Object.values(draft.counts).reduce((sum, n) => sum + n, 0);
}

/**
 * Why the choice can't be sent yet, or null when it can, as the server would check it (D182). When the DM chooses a
 * caster's creatures, the caster has only Conjure Animals' option to choose. `byDm` is the DM placing them.
 */
export function draftProblem(options: SummonOptions, draft: SummonDraft, byDm = false): string | null {
  if (options.dmChooses && !byDm) {
    return options.kind === 'CONJURE_ANIMALS' && !draft.optionId ? 'Choose an option' : null;
  }
  if (options.creatureTypes.length > 0 && !draft.creatureType) return 'Choose the creature type';
  if (options.kind === 'SPIRIT') {
    return options.variants.length > 0 && !draft.variant ? 'Choose the spirit' : null;
  }
  const option = options.options.find(o => o.id === draft.optionId);
  if (!option) return 'Choose an option';
  const total = totalCount(draft);
  if (total < 1) return 'Choose at least one creature';
  if (fewerAllowed(options) ? total > option.count : total !== option.count) {
    return `Choose ${fewerAllowed(options) ? 'up to ' : ''}${option.count} creature${option.count === 1 ? '' : 's'} (${total} chosen)`;
  }
  return null;
}

/**
 * The choice as a request carries it. A caster whose creatures the DM chooses sends only Conjure Animals' option, and
 * Summon Lesser Demons nothing, since its d6 picks the option (XGE p.167).
 */
export function toSummonChoice(options: SummonOptions, draft: SummonDraft, byDm = false): SummonChoice {
  if (options.dmChooses && !byDm) {
    return options.kind === 'LESSER_DEMONS' || !draft.optionId ? {} : { optionId: draft.optionId };
  }
  const creatures = options.kind === 'SPIRIT' ? undefined
    : Object.entries(draft.counts).filter(([, n]) => n > 0).map(([monsterId, count]) => ({ monsterId, count }));
  return {
    optionId: draft.optionId ?? undefined,
    creatures,
    variant: options.kind === 'SPIRIT' ? draft.variant ?? undefined : undefined,
    initiativeMode: draft.initiativeMode ?? undefined,
    creatureType: draft.creatureType ?? undefined,
  };
}

/** The creatures a Spell Effect summoned, among the participants the view shows. */
export function summonedCreatures(
  effect: { summonedParticipantIds: string[] }, participants: EncounterParticipant[],
): EncounterParticipant[] {
  const ids = new Set(effect.summonedParticipantIds);
  return participants.filter(p => ids.has(p.id));
}

/** The choice in words, for the confirm step: the creatures and their counts, the spirit, or the DM's to make. */
export function summonSummary(options: SummonOptions, draft: SummonDraft, byDm = false): string {
  if (options.dmChooses && !byDm) {
    const option = options.options.find(o => o.id === draft.optionId);
    return `the DM chooses the creatures${option && options.kind !== 'LESSER_DEMONS' ? ` (${option.label})` : ''}`;
  }
  if (options.kind === 'SPIRIT') {
    return [options.spirit?.name ?? options.spellName, draft.variant].filter(Boolean).join(', ');
  }
  const names = new Map(options.candidates.map(c => [c.monsterId, c.name]));
  const creatures = Object.entries(draft.counts).filter(([, n]) => n > 0)
    .map(([id, n]) => `${n} × ${names.get(id) ?? 'creature'}`).join(', ');
  return [creatures, draft.creatureType].filter(Boolean).join(', ');
}

/**
 * Whether a monster action is an attack, which a familiar can't make (PHB p.240): a multiattack, or one delivered by
 * an attack roll, as the server reads it.
 */
export function isAttackAction(action: { isMultiattack?: boolean; deliveryMethod?: string }): boolean {
  return !!action.isMultiattack || action.deliveryMethod?.toUpperCase() === 'ATTACK_ROLL';
}
