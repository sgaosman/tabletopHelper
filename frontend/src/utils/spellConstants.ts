// The subclasses that learn their spells from another class's list: the Eldritch Knight and the Arcane Trickster
// learn the wizard's (PHB p.75, p.98). The class tables themselves are the server's (useClassTables, D169).
const OTHER_CLASS_SPELL_LIST = new Map<string, string>([
  ['Eldritch Knight', 'Wizard'],
  ['Arcane Trickster', 'Wizard'],
]);

/** The class whose list a class's spells come from: the Wizard's for an Eldritch Knight or Arcane Trickster. */
export function spellListClass(className: string, subclassName?: string | null): string {
  return (subclassName && OTHER_CLASS_SPELL_LIST.get(subclassName)) || className;
}
