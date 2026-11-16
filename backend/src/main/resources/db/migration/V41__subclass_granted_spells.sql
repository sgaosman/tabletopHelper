-- D231: the spells a subclass grants outside its always-prepared list, written by the class loader from the 5etools
-- additionalSpells. "known" holds the spells it teaches by class level, such as a Gloom Stalker's disguise self at
-- Ranger 3 (XGE p.42), known with the source "subclass:" and its name and not counted against Spells Known; "options"
-- lists, for a subclass whose player picks one of several named lists, each list's name and its always-prepared and
-- known spells by class level, such as a Circle of the Land druid's terrain (PHB p.68-69) or a Divine Soul's affinity (XGE p.50).
-- Null for a subclass with neither. The class loader fills it on its next run, as its version rises.
ALTER TABLE subclasses ADD COLUMN IF NOT EXISTS granted_spells JSONB;
