import { useEffect, useMemo, useState } from 'react';
import { Check, ChevronDown, ChevronUp } from 'lucide-react';
import { useOptionalFeaturesOf, useSpellsByName } from '../../queries/reference';
import type { AsiChoice, PlayerCharacter } from '../../types/character';
import type { Feat, Spell } from '../../types/reference';
import {
  abilityIncrease, choicesNeeded, optionalFeatureTypes, resistanceOptions, skillOptions, spellSlots,
  type AbilityIncrease, type FeatEffects,
} from '../../utils/featData';
import FormattedDescription from '../FormattedDescription';
import SpellPicker from './SpellPicker';

const ABILITIES = [
  { key: 'strength', label: 'STR' },
  { key: 'dexterity', label: 'DEX' },
  { key: 'constitution', label: 'CON' },
  { key: 'intelligence', label: 'INT' },
  { key: 'wisdom', label: 'WIS' },
  { key: 'charisma', label: 'CHA' },
] as const;

const SCORE_OF: Record<string, (typeof ABILITIES)[number]['key']> = {
  STR: 'strength', DEX: 'dexterity', CON: 'constitution', INT: 'intelligence', WIS: 'wisdom', CHA: 'charisma',
};

// Every spell level, for a feat's spell of any level
const ALL_SPELL_LEVELS = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9];

interface FeatChoicesProps {
  feat: Feat;
  character: PlayerCharacter;
  /** The feat with every choice it asks for, or null while one is still to make */
  onSelectionChange: (selection: AsiChoice | null) => void;
  onBack: () => void;
}

/**
 * The choices one feat asks for: an ability, a resistance, skills, tools, languages, weapons, options such as
 * invocations, and its spells. Its parent keys it by the feat's ID, so picking another feat starts these afresh.
 */
export default function FeatChoices({ feat, character, onSelectionChange, onBack }: FeatChoicesProps) {
  const effects = feat.effects ?? null;
  const increase = useMemo(() => abilityIncrease(feat), [feat]);
  const needed = useMemo(() => choicesNeeded(effects, increase), [effects, increase]);
  const spellOptions = useMemo(() => feat.grantsFeatures ?? [], [feat]);

  const [showDescription, setShowDescription] = useState(false);
  const [abilityChoice, setAbilityChoice] = useState('');
  const [resistanceChoice, setResistanceChoice] = useState('');
  const [skillChoices, setSkillChoices] = useState<string[]>([]);
  const [savingThrowChoice, setSavingThrowChoice] = useState('');
  const [expertiseChoices, setExpertiseChoices] = useState<string[]>([]);
  const [toolChoices, setToolChoices] = useState<string[]>([]);
  const [languageChoices, setLanguageChoices] = useState<string[]>([]);
  const [weaponChoices, setWeaponChoices] = useState<string[]>([]);
  const [optionalFeatureChoices, setOptionalFeatureChoices] = useState<string[]>([]);
  // The spell list picked, and the spell chosen in each of its slots, by slot
  const [spellOption, setSpellOption] = useState(0);
  const [chosenSpells, setChosenSpells] = useState<Record<number, Spell>>({});
  const [activeSpellSlot, setActiveSpellSlot] = useState<number | null>(null);

  const featureTypes = useMemo(() => optionalFeatureTypes(effects), [effects]);
  const optionalFeatures = useOptionalFeaturesOf(needed.optionalFeatures > 0 ? featureTypes : []);

  const slots = useMemo(() => spellSlots(spellOptions[spellOption]), [spellOptions, spellOption]);
  // The spells the feat grants outright, looked up by name for their IDs
  const fixedSpells = useSpellsByName(slots.fixed);
  const spellIds = useMemo(() => [
    ...fixedSpells.filter((spell): spell is Spell => !!spell).map(spell => spell.id),
    ...Object.values(chosenSpells).map(spell => spell.id),
  ], [fixedSpells, chosenSpells]);

  const proficientSkills = character.skillProficiencies ?? [];
  const existingExpertise = character.skillExpertises ?? [];

  const selection = useMemo((): AsiChoice | null => {
    const complete = (!needed.ability || abilityChoice)
      && (!needed.resistance || resistanceChoice)
      && skillChoices.length >= needed.skill
      && (!needed.savingThrow || savingThrowChoice)
      && expertiseChoices.length >= needed.expertise
      && toolChoices.length >= needed.tool
      && languageChoices.length >= needed.language
      && weaponChoices.length >= needed.weapon
      && optionalFeatureChoices.length >= needed.optionalFeatures
      && Object.keys(chosenSpells).length >= slots.choices.length;
    if (!complete) return null;
    const asi: AsiChoice = { type: 'feat', featId: feat.id, featAbility: abilityChoice || undefined };
    if (resistanceChoice) asi.resistanceChoice = resistanceChoice;
    if (skillChoices.length) asi.skillProficiencyChoices = skillChoices;
    if (savingThrowChoice) asi.savingThrowChoice = savingThrowChoice;
    if (expertiseChoices.length) asi.expertiseSkillChoices = expertiseChoices;
    if (toolChoices.length) asi.toolProficiencyChoices = toolChoices;
    if (languageChoices.length) asi.languageChoices = languageChoices;
    if (weaponChoices.length) asi.weaponChoices = weaponChoices;
    if (optionalFeatureChoices.length) asi.optionalFeatureIds = optionalFeatureChoices;
    if (spellIds.length > 0) asi.spellIds = spellIds;
    return asi;
  }, [feat.id, needed, abilityChoice, resistanceChoice, skillChoices, savingThrowChoice, expertiseChoices,
    toolChoices, languageChoices, weaponChoices, optionalFeatureChoices, chosenSpells, slots, spellIds]);

  // The parent holds the selection the dialog sends
  useEffect(() => {
    onSelectionChange(selection);
  }, [selection, onSelectionChange]);

  function pickSpellOption(index: number) {
    setSpellOption(index);
    setChosenSpells({});
    setActiveSpellSlot(null);
  }

  return (
    <>
      <div className="flex items-center gap-2">
        <button type="button" onClick={onBack}
          className="font-body text-[15px] font-semibold text-muted hover:text-ink">&larr; Back</button>
        <h3 className="font-heading text-[17px] font-bold text-ink">{feat.name}</h3>
        <span className="font-body text-[13px] text-faint">{feat.source}</span>
      </div>

      <button type="button" onClick={() => setShowDescription(!showDescription)}
        className="flex items-center gap-1 font-body text-[13px] font-semibold text-muted hover:text-ink">
        {showDescription ? <ChevronUp className="w-3 h-3" /> : <ChevronDown className="w-3 h-3" />}
        {showDescription ? 'Hide' : 'Show'} description
      </button>
      {showDescription && feat.description && (
        <div className="bg-page-alt border border-rule p-3 max-h-40 overflow-y-auto">
          <FormattedDescription text={feat.description} className="text-xs [&_p]:text-muted" />
        </div>
      )}

      <EffectsSummary feat={feat} effects={effects} increase={increase} />

      <div className="space-y-3">
        {needed.ability && increase?.choose && (
          <ChoiceSection title={`Choose ability score +${increase.choose.amount}`}>
            <div className="flex flex-wrap gap-2">
              {increase.choose.from.map(ability => (
                <button key={ability} type="button" onClick={() => setAbilityChoice(ability)}
                  className={`px-3 py-1.5 font-body text-[15px] font-semibold border transition-colors ${
                    abilityChoice === ability ? 'bg-ink text-card border-ink' : 'bg-page border-rule text-muted hover:border-muted'
                  }`}>
                  {ability} ({SCORE_OF[ability.toUpperCase()] ? character[SCORE_OF[ability.toUpperCase()]] : '?'})
                </button>
              ))}
            </div>
          </ChoiceSection>
        )}

        {needed.resistance && (
          <ChoiceSection title="Choose damage resistance">
            <div className="flex flex-wrap gap-2">
              {resistanceOptions(effects).map(r => (
                <button key={r} type="button" onClick={() => setResistanceChoice(r)}
                  className={`px-3 py-1.5 font-body text-[15px] font-semibold capitalize border transition-colors ${
                    resistanceChoice === r ? 'bg-ink text-card' : 'bg-page-alt border border-rule text-muted hover:border-muted'
                  }`}>{r}</button>
              ))}
            </div>
          </ChoiceSection>
        )}

        {needed.skill > 0 && (
          <ChoiceSection title={`Choose ${needed.skill} skill proficienc${needed.skill > 1 ? 'ies' : 'y'}`}>
            <div className="flex flex-wrap gap-1.5">
              {skillOptions(effects, proficientSkills).map(skill => (
                <ToggleChip key={skill} label={skill} on={skillChoices.includes(skill)}
                  onClick={() => setSkillChoices(toggled(skill, skillChoices, needed.skill))} />
              ))}
            </div>
          </ChoiceSection>
        )}

        {needed.savingThrow && (
          <ChoiceSection title="Choose saving throw proficiency">
            <div className="flex flex-wrap gap-2">
              {ABILITIES.map(({ key, label }) => (
                <button key={key} type="button" onClick={() => setSavingThrowChoice(key)}
                  className={`px-3 py-1.5 font-body text-[15px] font-semibold transition-colors ${
                    savingThrowChoice === key ? 'bg-ink text-card' : 'bg-page-alt border border-rule text-muted hover:border-muted'
                  }`}>{label}</button>
              ))}
            </div>
          </ChoiceSection>
        )}

        {needed.expertise > 0 && (
          <ChoiceSection title={`Choose ${needed.expertise} skill${needed.expertise > 1 ? 's' : ''} for expertise`}>
            <div className="flex flex-wrap gap-1.5">
              {proficientSkills.filter(s => !existingExpertise.includes(s)).map(skill => (
                <ToggleChip key={skill} label={skill} on={expertiseChoices.includes(skill)}
                  onClick={() => setExpertiseChoices(toggled(skill, expertiseChoices, needed.expertise))} />
              ))}
            </div>
          </ChoiceSection>
        )}

        {needed.tool > 0 && (
          <TextChoices title={`Enter ${needed.tool} tool proficienc${needed.tool > 1 ? 'ies' : 'y'}`}
            count={needed.tool} placeholder="Tool" values={toolChoices} onChange={setToolChoices} />
        )}

        {needed.language > 0 && (
          <TextChoices title={`Enter ${needed.language} language${needed.language > 1 ? 's' : ''}`}
            count={needed.language} placeholder="Language" values={languageChoices} onChange={setLanguageChoices} />
        )}

        {needed.weapon > 0 && (
          <TextChoices title={`Enter ${needed.weapon} weapon proficienc${needed.weapon > 1 ? 'ies' : 'y'}`}
            count={needed.weapon} placeholder="Weapon" values={weaponChoices} onChange={setWeaponChoices} />
        )}

        {needed.optionalFeatures > 0 && optionalFeatures.length > 0 && (
          <ChoiceSection title={`Choose ${needed.optionalFeatures} option${needed.optionalFeatures > 1 ? 's' : ''}`}>
            <div className="space-y-1 max-h-40 overflow-y-auto">
              {optionalFeatures.map(feature => (
                <button key={feature.id} type="button"
                  onClick={() => setOptionalFeatureChoices(
                    toggled(feature.id, optionalFeatureChoices, needed.optionalFeatures))}
                  className={`w-full text-left px-3 py-2 font-body text-[15px] font-semibold transition-colors ${
                    optionalFeatureChoices.includes(feature.id) ? 'bg-ink text-card' : 'bg-page-alt border border-rule text-muted hover:border-muted'
                  }`}>
                  <div className="flex items-center justify-between">
                    <span className="font-semibold">{feature.name}</span>
                    {optionalFeatureChoices.includes(feature.id) && <Check className="w-4 h-4" />}
                  </div>
                  {feature.description && (
                    <p className="font-body text-[13px] text-muted mt-0.5 line-clamp-2">{feature.description}</p>
                  )}
                </button>
              ))}
            </div>
          </ChoiceSection>
        )}

        {spellOptions.length > 0 && (
          <ChoiceSection title="Feat Spells">
            {spellOptions.length > 1 && (
              <div className="mb-3">
                <p className="font-body text-[13px] text-faint mb-1.5">Choose a spell list:</p>
                <div className="flex flex-wrap gap-1.5">
                  {spellOptions.map((option, i) => (
                    <button key={i} type="button" onClick={() => pickSpellOption(i)}
                      className={`px-3 py-1.5 font-body text-[15px] font-semibold transition-colors ${
                        spellOption === i ? 'bg-ink text-card' : 'bg-page-alt border border-rule text-muted hover:border-muted'
                      }`}>{option.name || `Option ${i + 1}`}</button>
                  ))}
                </div>
              </div>
            )}

            {slots.fixed.length > 0 && (
              <div className="mb-2">
                <p className="font-body text-[13px] font-semibold text-faint mb-1">Granted automatically:</p>
                {slots.fixed.map((name, i) => (
                  <div key={i} className="flex items-center gap-2 px-3 py-1.5 bg-page-alt rounded font-body text-[15px] font-semibold text-buff">
                    <Check className="w-3.5 h-3.5" /> {name}
                  </div>
                ))}
              </div>
            )}

            {slots.choices.map((filter, i) => {
              const chosen = chosenSpells[i];
              const isActive = activeSpellSlot === i;
              const levelLabel = filter.level === 0 ? 'cantrip' : filter.level > 0 ? `level ${filter.level} spell` : 'spell';
              const filterDesc = filter.schools.length > 0
                ? `${filter.schools.join(' or ')} ${levelLabel}`
                : filter.classes.length > 0
                  ? `${filter.classes.join('/')} ${levelLabel}`
                  : filter.fromList ? `${levelLabel} from list` : levelLabel;
              // The other slots' spells can't be chosen again here
              const otherSlots = Object.entries(chosenSpells).filter(([slot]) => Number(slot) !== i).map(([, spell]) => spell.name);

              return (
                <div key={i} className="mb-2">
                  <button
                    type="button"
                    onClick={() => setActiveSpellSlot(isActive ? null : i)}
                    className={`w-full text-left px-3 py-2 font-body text-[15px] font-semibold transition-colors border ${
                      chosen ? 'border-2 border-ink bg-page-alt text-ink' : 'border-rule bg-page-alt text-ink hover:bg-rule'
                    }`}
                  >
                    {chosen
                      ? <span className="flex items-center gap-2"><Check className="w-3.5 h-3.5 text-ink" /> {chosen.name}</span>
                      : <span>Choose a {filterDesc}...</span>}
                  </button>

                  {isActive && (
                    <div className="mt-1 border border-rule bg-card px-2 pt-2">
                      <SpellPicker
                        value={chosen ? [chosen.name] : []}
                        onChange={(next, spell) => {
                          const updated = { ...chosenSpells };
                          if (next.length > 0) updated[i] = spell; else delete updated[i];
                          setChosenSpells(updated);
                          if (next.length > 0) setActiveSpellSlot(null);
                        }}
                        max={1}
                        filter={{
                          className: filter.classes[0],
                          levels: filter.level >= 0 ? [filter.level] : ALL_SPELL_LEVELS,
                          schools: filter.schools,
                          names: filter.fromList,
                        }}
                        statusOf={spell => otherSlots.some(name => name === spell.name)
                          ? { status: 'Chosen', disabled: true } : undefined}
                        placeholder={`Search ${filterDesc}...`}
                      />
                    </div>
                  )}
                </div>
              );
            })}
          </ChoiceSection>
        )}
      </div>
    </>
  );
}

function ChoiceSection({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="bg-page-alt p-3">
      <p className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-muted mb-2">{title}</p>
      {children}
    </div>
  );
}

function ToggleChip({ label, on, onClick }: { label: string; on: boolean; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} aria-pressed={on}
      className={`px-2 py-1 rounded font-heading text-[11px] font-semibold tracking-[0.02em] transition-colors ${
        on ? 'bg-ink text-card' : 'bg-page-alt text-muted hover:bg-rule'
      }`}>{label}</button>
  );
}

function TextChoices({ title, count, placeholder, values, onChange }: {
  title: string; count: number; placeholder: string; values: string[]; onChange: (values: string[]) => void;
}) {
  return (
    <ChoiceSection title={title}>
      {Array.from({ length: count }).map((_, i) => (
        <input key={i} value={values[i] || ''} aria-label={`${placeholder} ${i + 1}`}
          onChange={e => {
            const updated = [...values];
            updated[i] = e.target.value;
            onChange(updated.filter(Boolean));
          }}
          placeholder={`${placeholder} ${i + 1}...`}
          className="w-full bg-page-alt border border-rule px-3 py-1.5 font-body text-[16px] font-semibold text-ink placeholder-faint focus:outline-none focus:border-muted mb-1" />
      ))}
    </ChoiceSection>
  );
}

function EffectsSummary({ feat, effects, increase }: {
  feat: Feat; effects: FeatEffects | null; increase: AbilityIncrease | null;
}) {
  const tags: string[] = [];
  if (increase) {
    tags.push(...Object.entries(increase.fixed).map(([ability, amount]) => `+${amount} ${ability}`));
    if (increase.choose) tags.push(`+${increase.choose.amount} to choice`);
  }
  if (effects) {
    if (effects.resistances?.length) tags.push('Resistance');
    if (effects.armorProficiencies?.length) tags.push('Armor Prof.');
    if (effects.weaponProficiencies?.length) tags.push('Weapon Prof.');
    if (effects.toolProficiencies?.length) tags.push('Tool Prof.');
    if (effects.skillProficiencies?.length) tags.push('Skill Prof.');
    if (effects.languageProficiencies?.length) tags.push('Language');
    if (effects.savingThrowProficiencies?.length) tags.push('Save Prof.');
    if (effects.expertise?.length) tags.push('Expertise');
    if (effects.speedBonus) tags.push(`+${effects.speedBonus} Speed`);
    if (effects.initiativeBonus) tags.push(`+${effects.initiativeBonus} Initiative`);
    if (effects.hpPerLevel) tags.push(`+${effects.hpPerLevel} HP/level`);
    if (effects.passivePerceptionBonus) tags.push(`+${effects.passivePerceptionBonus} Passive Perc.`);
    if (effects.resource) tags.push(effects.resource.name);
    if (effects.optionalFeatureProgression?.length) tags.push('Feature Choice');
    if (effects.skillToolLanguageProficiencies?.length) tags.push('Skill/Tool Choice');
  }
  if (feat.grantsFeatures?.length) tags.push('Spells');
  if (tags.length === 0) return null;

  return (
    <div className="flex flex-wrap gap-1.5">
      {tags.map(tag => (
        <span key={tag} className="px-2 py-0.5 bg-page-alt border border-rule font-heading text-[11px] font-semibold tracking-[0.02em] text-ink">{tag}</span>
      ))}
    </div>
  );
}

// The list with the value added, up to the most allowed, or taken out
function toggled(value: string, current: string[], max: number): string[] {
  if (current.includes(value)) return current.filter(v => v !== value);
  return current.length < max ? [...current, value] : current;
}
