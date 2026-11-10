"""Adds the spellcasting of bestiary monsters that monster-action-definitions.json leaves without any (D193).

A monster the bestiary loads (not a _copy, the first of its name and source) whose stat block has a `spellcasting`
array, but whose definition is missing or has no `spellcasting` block, gets one built from the stat block in the
format the existing entries use:

  name, ability ("INT"), saveDC, attackBonus, innateSpells (true for an innate block), atWillSpells (the `will`
  list), dailySpells (the `daily` lists, under the stat block's own keys such as "1e" or "3"), slots and
  spellsByLevel (the `spells` levels, "0" for cantrips), and notes.

The save DC and attack bonus come from the header's {@dc N} and {@hit N}. Without a DC it is worked out as
8 + proficiency bonus + ability modifier (PHB p.205) and marked _dcComputed; without an attack bonus it is the DC
less 8, the same proficiency bonus and modifier, and marked _attackBonusComputed, as the existing entries mark theirs.

A monster with more than one block keeps them apart: the block with spell slots, or else the first, is
`spellcasting`, and the others go in `additionalSpellcasting`, which MonsterSpellbook merges. A block without spell
lists, such as a staff's charges, isn't castable and is named in the notes instead, as are footer reminders.

New entries are appended in bestiary order (files by name), so running the script again changes nothing.

Usage, from backend/:  python scripts/generate_monster_spellcasting.py [--check]
--check only reports what would be added, and exits 1 if anything would be.
"""
import glob
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
DATA = os.path.join(HERE, '..', 'src', 'main', 'resources', 'data')
DEFINITIONS = os.path.join(DATA, 'monster-actions', 'monster-action-definitions.json')
BESTIARY = os.path.join(DATA, '5etools', 'bestiary', 'bestiary-*.json')

ABILITIES = {'strength': 'STR', 'dexterity': 'DEX', 'constitution': 'CON', 'intelligence': 'INT',
             'wisdom': 'WIS', 'charisma': 'CHA'}
SCORE_KEYS = {'STR': 'str', 'DEX': 'dex', 'CON': 'con', 'INT': 'int', 'WIS': 'wis', 'CHA': 'cha'}
TAG = re.compile(r'\{@(\w+)\s+([^{}]*?)\}')
DC = re.compile(r'\{@dc\s+(\d+)\}')
HIT = re.compile(r'\{@hit\s+(\d+)\}')
ABILITY_WORD = re.compile(r'\b(Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma)\b')
# The lists a block can cast from; any other list key is named in the notes
CAST_KEYS = ('spells', 'will', 'daily')
OTHER_LIST_KEYS = ('rest', 'restLong', 'weekly', 'monthly', 'yearly', 'recharge', 'charges', 'legendary', 'ritual')
RATING = re.compile(r'^(\d+)(?:/(\d+))?$')


def strip_tags(text):
    """{@spell plane shift|phb} (self only) -> plane shift (self only); {@spell a|b|shown} -> shown."""
    def replace(match):
        parts = match.group(2).split('|')
        if match.group(1) == 'spell' and len(parts) >= 3 and parts[2].strip():
            return parts[2]
        if match.group(1) == 'dc':
            return 'DC ' + parts[0]
        if match.group(1) == 'hit':
            return '+' + parts[0]
        return parts[0]
    previous = None
    while previous != text:
        previous, text = text, TAG.sub(replace, text)
    return text.strip()


def entry_text(entry):
    if isinstance(entry, str):
        return strip_tags(entry)
    if isinstance(entry, dict):
        if 'entry' in entry:
            return entry_text(entry['entry'])
        if 'entries' in entry:
            return ' '.join(entry_text(e) for e in entry['entries'])
    return ''


def names(items):
    result = []
    for item in items or []:
        name = entry_text(item)
        if name and name not in result:
            result.append(name)
    return result


def rating(monster):
    cr = monster.get('cr')
    if isinstance(cr, dict):
        cr = cr.get('cr')
    return cr if isinstance(cr, str) else None


# The proficiency bonus of a challenge rating (MM p.8): +2 up to CR 4, one more each 4 ratings after
def proficiency_bonus(cr):
    match = RATING.match(cr.strip()) if cr else None
    whole = int(match.group(1)) if match and not match.group(2) else 0
    return 2 + max(0, (max(whole, 1) - 1) // 4)


def header(block):
    return ' '.join(entry_text(e) for e in block.get('headerEntries', []))


def ability_of(block):
    if isinstance(block.get('ability'), str):
        return block['ability'].strip().upper()[:3]
    found = ABILITY_WORD.search(header(block))
    return ABILITIES[found.group(1).lower()] if found else None


def castable(block):
    return any(block.get(key) for key in CAST_KEYS)


def raw_header(block):
    return ' '.join(e for e in block.get('headerEntries', []) if isinstance(e, str))


def convert(block, monster):
    """One stat-block spellcasting block in the definitions' format."""
    text = raw_header(block)
    ability = ability_of(block)
    result = {'name': block.get('name', 'Spellcasting')}
    if ability:
        result['ability'] = ability
    dc = DC.search(text)
    hit = HIT.search(text)
    modifier = None
    if ability and isinstance(monster.get(SCORE_KEYS[ability]), int):
        modifier = (monster[SCORE_KEYS[ability]] - 10) // 2
    if dc:
        result['saveDC'] = int(dc.group(1))
    elif modifier is not None:
        result['saveDC'] = 8 + proficiency_bonus(rating(monster)) + modifier
        result['_dcComputed'] = True
    if hit:
        result['attackBonus'] = int(hit.group(1))
    elif 'saveDC' in result:
        result['attackBonus'] = result['saveDC'] - 8
        result['_attackBonusComputed'] = True
    result['innateSpells'] = 'innate' in result['name'].lower()
    if block.get('will'):
        result['atWillSpells'] = names(block['will'])
    if block.get('daily'):
        result['dailySpells'] = {key: names(spells) for key, spells in block['daily'].items()}
    levels = block.get('spells') or {}
    slots = {level: data['slots'] for level, data in levels.items()
             if isinstance(data, dict) and isinstance(data.get('slots'), int) and data['slots'] > 0}
    if slots:
        result['slots'] = slots
    if levels:
        result['spellsByLevel'] = {level: names(data.get('spells')) for level, data in levels.items()
                                   if isinstance(data, dict)}
    notes = [entry_text(e) for e in block.get('footerEntries', [])]
    other = [key for key in OTHER_LIST_KEYS if block.get(key)]
    if other:
        notes.append('Lists not read: ' + ', '.join(other) + '.')
    if notes:
        result['notes'] = ' '.join(n for n in notes if n)
    return result


def definition(monster, existing):
    blocks = [b for b in monster['spellcasting'] if isinstance(b, dict)]
    usable = [b for b in blocks if castable(b)]
    if not usable:
        return None
    main = next((b for b in usable if any(isinstance(d, dict) and d.get('slots')
                                          for d in (b.get('spells') or {}).values())), usable[0])
    converted = convert(main, monster)
    others = [convert(b, monster) for b in usable if b is not main]
    notes = [converted['notes']] if 'notes' in converted else []
    for other in others:
        if (other.get('ability'), other.get('saveDC')) != (converted.get('ability'), converted.get('saveDC')):
            notes.append('%s uses %s, spell save DC %s.' % (other['name'], other.get('ability', '?'),
                                                            other.get('saveDC', '?')))
    for block in blocks:
        if block not in usable:
            notes.append('%s has no spell lists to cast from; see the stat block.' % block.get('name', 'A block'))
    if notes:
        converted['notes'] = ' '.join(notes)
    entry = dict(existing) if existing else {'monsterName': monster['name'], 'source': monster['source'],
                                             'challengeRating': rating(monster)}
    entry['spellcasting'] = converted
    if others:
        entry['additionalSpellcasting'] = others
    return entry


def key(name, source):
    return name.strip().lower() + '|' + source.strip().lower()


def main():
    check = '--check' in sys.argv
    with open(DEFINITIONS, 'rb') as f:
        raw = f.read()
    crlf = b'\r\n' in raw[:200]
    definitions = json.loads(raw.decode('utf-8'))
    index = {}
    for position, entry in enumerate(definitions):
        index.setdefault(key(entry.get('monsterName', ''), entry.get('source', '')), position)

    seen = set()
    added, filled, skipped = [], [], []
    for path in sorted(glob.glob(BESTIARY)):
        name = os.path.basename(path)
        if 'fluff' in name or 'index' in name:
            continue
        with open(path, encoding='utf-8') as f:
            monsters = json.load(f).get('monster', [])
        for monster in monsters:
            if '_copy' in monster:
                continue
            k = key(monster.get('name', ''), monster.get('source', ''))
            if k in seen:
                continue
            seen.add(k)
            if not monster.get('spellcasting'):
                continue
            position = index.get(k)
            existing = definitions[position] if position is not None else None
            if existing and existing.get('spellcasting'):
                continue
            entry = definition(monster, existing)
            if entry is None:
                skipped.append(monster['name'] + ' (' + monster['source'] + ')')
            elif existing:
                definitions[position] = entry
                filled.append(entry)
            else:
                definitions.append(entry)
                index[k] = len(definitions) - 1
                added.append(entry)

    print('added %d definitions, filled in %d, %d without castable lists' % (len(added), len(filled), len(skipped)))
    for monster in skipped:
        print('  not castable: ' + monster)
    if check:
        sys.exit(1 if added or filled else 0)
    if added or filled:
        text = json.dumps(definitions, indent=2, ensure_ascii=False)
        if crlf:
            text = text.replace('\n', '\r\n')
        with open(DEFINITIONS, 'wb') as f:
            f.write(text.encode('utf-8'))


if __name__ == '__main__':
    main()
