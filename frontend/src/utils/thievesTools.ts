/**
 * Thieves' tools as a Rogue's Expertise (PHB p.96): "choose two of your skill proficiencies, or one of your skill
 * proficiencies and your proficiency with thieves' tools", and again at 6th level. A Bard's Expertise takes skills only
 * (PHB p.54). The server keeps the tool's Expertise with the skills', under this name.
 */
export const THIEVES_TOOLS = "Thieves' Tools";

/** Whether the name is thieves' tools, in any case, with a straight or curly apostrophe. */
export function isThievesTools(name: string | null | undefined): boolean {
  return !!name && name.trim().replace(/’/g, "'").toLowerCase() === "thieves' tools";
}

/** Whether a list of tool proficiencies, or of Expertise, names thieves' tools. */
export function hasThievesTools(names: readonly string[] | null | undefined): boolean {
  return (names ?? []).some(isThievesTools);
}

/** Whether the class's Expertise can take thieves' tools: a Rogue's only. */
export function expertiseTakesThievesTools(className: string | null | undefined): boolean {
  return className?.trim().toLowerCase() === 'rogue';
}
