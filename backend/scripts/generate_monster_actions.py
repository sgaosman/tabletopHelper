"""Builds the action templates of the bestiary monsters D193 gave spellcasting only (D194).

D193 appended 126 definitions to monster-action-definitions.json that hold a monster's spellcasting and nothing else.
This script reads each one's stat block from the 5etools bestiary and writes its actions, bonus actions and reactions
in the format the hand-built entries use, and marks the entry `_actionsFromStatBlock` so a later run finds it again.
An entry is a target when it has that mark, or when it holds spellcasting and nothing else (only its name, source,
challenge rating and spellcasting blocks); every other entry is left exactly as it is.

What is read from the stat block's text:
  - an attack ({@atk mw} and the like): attackType, attackBonus ({@hit N}), reach, range and rangeLong, targetCount,
    and the damage of its hit as DAMAGE parts, one per "N (XdY + Z) type damage", with a "... if used with two hands"
    alternative as `versatile`. A grapple on a hit is a grappled CONDITION with its escapeDC; a condition the hit
    gives on a failed save keeps that save on the condition, with how long it lasts and any save at the end of each
    turn. Anything else the hit does is the DM's, in dmNote.
  - a saving throw (DC N Ability saving throw, with no attack): saveDC, saveAbility, halfOnSave, the damage parts,
    the conditions a failed save gives, and the area (cone, line, cube, cylinder or sphere). A save with more than one
    DC or ability, a choice of ability, or one triggered by entering an area or starting a turn there is the DM's.
  - a recharge in the name ({@recharge 5} is "5-6"), and "(N/Day)" kept in the name with usesPerDay, as the existing
    entries write them; "Recharges after a Short or Long Rest" stays in the name, which LimitedUsage reads.
  - a multiattack's components, by matching the actions' names in its text with their counts ("two Claw attacks",
    "one with its bite"); a generic "two melee attacks" becomes each fitting attack, and alternatives ("X or Y") list
    every option, as the existing entries do, so the DM skips the ones not used. Sentences that only allow a
    replacement ("It can replace one attack with a use of Spellcasting") are not read.
  - Parry and Uncanny Dodge reactions, which are the DM's with the existing wording of what to restore by hand.
  - Legendary Resistance (N/Day) as legendaryResistanceCount, with its trait.
Anything else is the DM's: automatable false with the stat block's description, as existing entries do.

Usage, from backend/:  python scripts/generate_monster_actions.py [--check]
--check writes nothing and exits 1 if the file would change.
"""
import glob
import json
import os
import re
import sys

from generate_monster_spellcasting import BESTIARY, DEFINITIONS, key, strip_tags

MARK = '_actionsFromStatBlock'
# The keys of an entry D193 appended, which holds a monster's spellcasting and nothing else
SPELLCASTING_ONLY = {'monsterName', 'source', 'challengeRating', 'spellcasting', 'additionalSpellcasting'}
ABILITIES = {'strength': 'STR', 'dexterity': 'DEX', 'constitution': 'CON', 'intelligence': 'INT',
             'wisdom': 'WIS', 'charisma': 'CHA'}
ATTACK_TYPES = {'mw': 'MELEE_WEAPON', 'rw': 'RANGED_WEAPON', 'mw,rw': 'MELEE_OR_RANGED_WEAPON',
                'ms': 'MELEE_SPELL', 'rs': 'RANGED_SPELL', 'ms,rs': 'MELEE_OR_RANGED_SPELL',
                'm': 'MELEE_WEAPON', 'r': 'RANGED_WEAPON', 'm,r': 'MELEE_OR_RANGED_WEAPON'}
CONDITIONS = {'blinded', 'charmed', 'deafened', 'frightened', 'grappled', 'incapacitated', 'invisible', 'paralyzed',
              'petrified', 'poisoned', 'prone', 'restrained', 'stunned', 'unconscious'}
COUNTS = {'a': 1, 'an': 1, 'one': 1, 'two': 2, 'three': 3, 'four': 4, 'five': 5, 'six': 6, 'seven': 7, 'eight': 8,
          'once': 1, 'twice': 2, 'thrice': 3}
AREAS = [(re.compile(r'(\d+)-foot-radius,?\s+[\d-]*(?:foot-tall\s+)?cylinder'), 'AOE_CYLINDER'),
         (re.compile(r'(\d+)-foot(?:-long)?\s+(?:wide\s+)?line'), 'AOE_LINE'),
         (re.compile(r'(\d+)-foot\s+cone'), 'AOE_CONE'),
         (re.compile(r'(\d+)-foot\s+cube'), 'AOE_CUBE'),
         (re.compile(r'(\d+)-foot[- ]radius'), 'AOE_SPHERE')]

ATK = re.compile(r'\{@atkr? ([a-z,]+)\}')
HIT = re.compile(r'\{@hit (-?\d+)\}')
REACH = re.compile(r'reach (\d+) ft')
RANGE = re.compile(r'range (\d+)(?:/(\d+))? ft')
TARGETS = re.compile(r'\b(one|two|three|four) (?:target|creature)s?\b')
RECHARGE = re.compile(r'\s*\{@recharge(?: (\d))?\}')
PER_DAY = re.compile(r'\((\d+)/Day(?: each)?\)', re.I)
# "22 ({@damage 4d6 + 8}) piercing damage", the dice sometimes untagged, the type possibly a choice ("lightning or
# thunder", "acid, cold, lightning, or fire")
DAMAGE = re.compile(r'(\d+)\s*\((?:\{@damage )?(\d+d\d+(?:\s*[+-]\s*\d+)?)\}?\)\s*'
                    r'([a-z]+(?:(?:, or |, | or )[a-z]+)*)\s+damage')
FLAT_DAMAGE = re.compile(r'^\s*(\d+) ([a-z]+) damage')
VERSATILE = re.compile(r',?\s*or (\d+) \(\{@damage ([^}]+)\}\) ([a-z]+) damage if used with two hands')
SAVE = re.compile(r'\{@dc (\d+)\}\s+(Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma)'
                  r'(?: or (Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma))?\s+saving throw')
ACT_SAVE = re.compile(r'\{@actSave (str|dex|con|int|wis|cha)\}\s*\{@dc (\d+)\}')
GRAPPLE = re.compile(r'\{@condition grappled\}(?: condition)?\s*\(escape \{@dc (\d+)\}\)')
# A hit that restrains until a Strength check breaks free, as a web does
RESTRAINED = re.compile(r'\{@condition restrained\}[^.]*\.[^.]*?\{@dc (\d+)\} Strength check')
PLAIN_RECHARGE = re.compile(r'[;\s]*Recharge (\d)(?:\s*[\u2013-]\s*6)?')
ATTACK_WORDS = {'mw': 'Melee Weapon Attack:', 'rw': 'Ranged Weapon Attack:', 'mw,rw': 'Melee or Ranged Weapon Attack:',
                'ms': 'Melee Spell Attack:', 'rs': 'Ranged Spell Attack:', 'ms,rs': 'Melee or Ranged Spell Attack:'}
# "or be {@condition poisoned}", "is knocked {@condition prone}", with any conditions joined to it ("and {@condition X}")
CONDITION = re.compile(r'\b(?:be|become|becomes|is|are|knocked|falls?)\s+(?:knocked\s+)?\{@condition (\w+)\}'
                       r'((?:,?\s*(?:and|or)?\s*\{@condition \w+\})*)')
JOINED = re.compile(r'\{@condition (\w+)\}')
# What a save action does beyond damage and conditions, which the DM applies by hand
RIDER = re.compile(r"exhaustion|disadvantage|advantage|hit point maximum|can't regain|push|pull|teleport|speed|"
                   r"reduced to 0 hit points|swallow|possess|instead|choice", re.I)
OPTIONS = re.compile(r'\bone of the following\b|\bfollowing (?:benefits|options|effects)\b', re.I)
SENTENCE = re.compile(r'(?<=[.!])\s+(?=[A-Z{])')
REPEAT = re.compile(r'repeat the saving throw at the end of each of its turns', re.I)
TRIGGERED = re.compile(r'\b(?:that enters|enters the|starts its turn|start of each of its turns)\b')
PARRY = ('The attack was already resolved as a hit: if the higher AC makes it miss, the DM restores its damage and '
         'removes anything else the hit did, by hand.')
UNCANNY_DODGE = 'The damage was already applied in full: the DM restores the part this reaction prevents, by hand.'


def flatten(entries):
    """The raw text of a stat-block entry list, tags kept: strings, nested entries and list items."""
    parts = []
    for entry in entries or []:
        if isinstance(entry, str):
            parts.append(entry)
        elif isinstance(entry, dict):
            if entry.get('name') and entry.get('type') == 'item':
                parts.append(entry['name'])
            for k in ('entry',):
                if isinstance(entry.get(k), str):
                    parts.append(entry[k])
            parts.append(flatten(entry.get('entries')))
            parts.append(flatten(entry.get('items')))
    return ' '.join(p for p in parts if p)


def duration(text):
    """How long a condition lasts, in rounds and words, from the sentence that gives it."""
    lowered = text.lower()
    if re.search(r'for 1 minute', lowered):
        return 10, '1 minute'
    if re.search(r'for 1 hour', lowered):
        return 600, '1 hour'
    if re.search(r'until the (?:end|start) of (?:its|the target\'s|the [a-z]+\'s) next turn', lowered):
        return 1, 'until the end of its next turn'
    return None, None


def conditions(text, save=None):
    """The conditions a sentence gives, each a CONDITION part; a save given here is kept on each."""
    found = []
    for match in CONDITION.finditer(text):
        for name in [match.group(1)] + JOINED.findall(match.group(2) or ''):
            name = name.lower()
            if name in CONDITIONS and name not in [f['condition'] for f in found]:
                found.append({'effectType': 'CONDITION', 'condition': name})
    rounds, words = duration(text)
    repeat = bool(REPEAT.search(text))
    for part in found:
        if save:
            part['saveDC'], part['saveAbility'] = save
        if words:
            part['durationText'] = words
            part['durationRounds'] = rounds
        if repeat and save:
            part['saveToEndEachTurn'] = True
            part['saveToEndDC'], part['saveToEndAbility'] = save
    return found


def damage_parts(text):
    """The damage parts of a text, and whether any is a choice of type or an alternative the DM settles."""
    parts, choice, previous_end = [], False, 0
    for match in DAMAGE.finditer(text):
        between = text[previous_end:match.start()]
        previous_end = match.end()
        if parts and re.search(r'\bor\b', between):
            choice = True
            continue
        damage_type = match.group(3)
        if ' or ' in damage_type or ',' in damage_type:
            choice = True
            damage_type = damage_type.split(',')[0].split(' or ')[0]
        parts.append({'effectType': 'DAMAGE', 'damageDice': match.group(2).strip(), 'damageType': damage_type})
    return parts, choice


def saves(text):
    found = []
    for match in SAVE.finditer(text):
        found.append((int(match.group(1)), ABILITIES[match.group(2).lower()], match.group(3)))
    for match in ACT_SAVE.finditer(text):
        found.append((int(match.group(2)), match.group(1).upper(), None))
    return found


def parse_name(raw):
    """The action's name as the definitions write it, and its recharge and daily uses."""
    recharge = None
    match = RECHARGE.search(raw)
    if match:
        low = int(match.group(1)) if match.group(1) else 6
        recharge = '6' if low >= 6 else '%d-6' % low
        raw = RECHARGE.sub('', raw)
    name = strip_tags(raw).strip()
    plain = PLAIN_RECHARGE.search(name)
    if plain and not recharge:
        low = int(plain.group(1))
        recharge = '6' if low >= 6 else '%d-6' % low
        name = PLAIN_RECHARGE.sub('', name)
    name = re.sub(r'[;\s]+\)', ')', name).replace('()', '').strip()
    per_day = PER_DAY.search(name)
    return name, recharge, int(per_day.group(1)) if per_day else None


def describe(raw):
    """The stat block's text as it reads: "Melee Weapon Attack: +14 to hit, ... Hit: 22 (4d6 + 8) piercing damage."
    """
    text = re.sub(r'\{@atkr? ([a-z,]+)\}', lambda m: ATTACK_WORDS.get(m.group(1), 'Attack:'), raw)
    text = re.sub(r'\{@h\}\s*', 'Hit: ', text)
    return strip_tags(text)


def manual(action, description, note=None):
    action['automatable'] = False
    action['description'] = description
    if note:
        action['dmNote'] = note
    return action


def attack(action, raw, description):
    head, _, hit = raw.partition('{@h}')
    action['deliveryMethod'] = 'ATTACK_ROLL'
    action['attackType'] = ATTACK_TYPES.get(ATK.search(head).group(1), 'MELEE_WEAPON')
    bonus = HIT.search(head)
    if not bonus:
        return manual(action, description, 'The attack bonus is not in the stat block; the DM rolls it.')
    action['attackBonus'] = int(bonus.group(1))
    reach = REACH.search(head)
    if reach:
        action['reach'] = int(reach.group(1))
    reach_range = RANGE.search(head)
    if reach_range:
        action['range'] = int(reach_range.group(1))
        if reach_range.group(2):
            action['rangeLong'] = int(reach_range.group(2))
    targets = TARGETS.search(strip_tags(head))
    action['targetCount'] = COUNTS[targets.group(1)] if targets else 1

    sentences = SENTENCE.split(hit.strip())
    first, rest = sentences[0], sentences[1:]
    versatile = VERSATILE.search(first)
    if versatile:
        action['versatile'] = {'twoHandedDamageDice': versatile.group(2).strip(),
                               'twoHandedDamageType': versatile.group(3)}
        first = first[:versatile.start()] + first[versatile.end():]
    effects, choice = damage_parts(first)
    if not effects:
        flat = FLAT_DAMAGE.match(strip_tags(first))
        if flat:
            effects.append({'effectType': 'DAMAGE', 'damageDice': flat.group(1), 'damageType': flat.group(2),
                            'flatDamage': True})
    grapple = GRAPPLE.search(hit)
    if grapple:
        effects.append({'effectType': 'CONDITION', 'condition': 'grappled', 'escapeDC': int(grapple.group(1))})
    leftover = []
    for sentence in rest:
        found = saves(sentence)
        if len(found) == 1 and not found[0][2] and not DAMAGE.search(sentence) and 'swallow' not in sentence:
            # The conditions come from this sentence; how long they last can be said in the next ones
            given = [c for c in conditions(sentence, found[0][:2]) if c['condition'] != 'grappled']
            later = conditions(' '.join([sentence] + rest), found[0][:2])
            for c in given:
                c.update({k: v for k, v in next(x for x in later if x['condition'] == c['condition']).items()
                          if k not in c})
            if given:
                effects.extend(given)
                if RIDER.search(sentence):
                    leftover.append(sentence)
                continue
        leftover.append(sentence)
    restrained = RESTRAINED.search(hit)
    if not effects and restrained:
        effects.append({'effectType': 'CONDITION', 'condition': 'restrained', 'escapeDC': int(restrained.group(1))})
    if not effects:
        return manual(action, description, 'The hit\'s effect is not damage the app can roll: ' + describe(hit))
    action['effects'] = effects
    notes = []
    if choice:
        notes.append('On a hit: ' + strip_tags(first) + ' The DM settles the choice by hand.')
    leftover_text = strip_tags(' '.join(s for s in leftover if not GRAPPLE.search(s) or DAMAGE.search(s)))
    if leftover_text:
        notes.append('Also on a hit, applied by hand: ' + leftover_text)
    if notes:
        action['dmNote'] = ' '.join(notes)
    action['description'] = description
    return action


def saving_throw(action, raw, description):
    found = saves(raw)
    pairs = {(dc, ability) for dc, ability, _ in found}
    if len(pairs) != 1 or any(other for _, _, other in found):
        return manual(action, description)
    sentences = SENTENCE.split(raw)
    if TRIGGERED.search(strip_tags(raw)) or OPTIONS.search(raw) or len(sentences) > 5:
        return manual(action, description)
    dc, ability = next(iter(pairs))
    # A sentence about a successful save only, such as "On a successful save, it takes 22 (5d8) psychic damage",
    # gives the success damage, not more damage
    success_only = [s for s in sentences if re.search(r'success', s) and not re.search(r'fail', s)]
    effects, choice = damage_parts(' '.join(s for s in sentences if s not in success_only))
    success_damage, _ = damage_parts(' '.join(success_only))
    effects.extend(conditions(' '.join(s for s in SENTENCE.split(raw)
                                       if 'saving throw' in s or 'failed save' in s or 'condition' in s),
                              (dc, ability)))
    for part in effects:
        if part['effectType'] == 'CONDITION':
            part.pop('saveDC', None)
            part.pop('saveAbility', None)
    if not effects:
        return manual(action, description)
    action['deliveryMethod'] = 'SAVING_THROW'
    action['saveDC'] = dc
    action['saveAbility'] = ability
    action['halfOnSave'] = bool(re.search(r'half as much|half damage', raw)) or halved(effects, success_damage)
    text = strip_tags(raw)
    for pattern, kind in AREAS:
        area = pattern.search(text)
        if area:
            action['targetType'] = kind
            action['aoeSize'] = int(area.group(1))
            break
    else:
        targets = re.search(r'\b(one|two|three) (?:creature|target)s?\b', text)
        if targets:
            action['targetCount'] = COUNTS[targets.group(1)]
    action['effects'] = effects
    if choice:
        action['dmNote'] = 'The damage type or amount is a choice the DM settles by hand: ' + description
    elif RIDER.search(description):
        action['dmNote'] = 'Applied by hand beyond the damage and conditions: ' + description
    action['description'] = description
    return action


def average(dice):
    match = re.match(r'^(\d+)d(\d+)(?:\s*([+-])\s*(\d+))?$', dice.strip())
    if not match:
        return None
    flat = int(match.group(4) or 0) * (-1 if match.group(3) == '-' else 1)
    return int(match.group(1)) * (int(match.group(2)) + 1) / 2 + flat


def halved(failed, succeeded):
    """Whether the success damage is the failed save's halved, rounded down, as "22 (5d8)" is of "45 (10d8)"."""
    fail = [p for p in failed if p['effectType'] == 'DAMAGE']
    if len(fail) != 1 or len(succeeded) != 1:
        return False
    whole, half = average(fail[0]['damageDice']), average(succeeded[0]['damageDice'])
    return whole is not None and half is not None and int(half) == int(whole) // 2


def build(entry, action_type, monster_name):
    if not isinstance(entry, dict) or not entry.get('name'):
        return None
    name, recharge, per_day = parse_name(entry['name'])
    raw = flatten(entry.get('entries'))
    description = describe(raw)
    action = {'name': name, 'actionType': action_type}
    if recharge:
        action['recharge'] = recharge
    if per_day:
        action['usesPerDay'] = per_day
    if action_type == 'REACTION' and name in ('Parry', 'Uncanny Dodge'):
        return manual(action, description, description + ' ' + (PARRY if name == 'Parry' else UNCANNY_DODGE))
    if ATK.search(raw):
        return attack(action, raw, description)
    if saves(raw):
        return saving_throw(action, raw, description)
    return manual(action, description)


def base_name(name):
    return re.sub(r'\s*\([^()]*\)\s*$', '', name).strip()


def multiattack(entry, actions):
    raw = flatten(entry.get('entries'))
    text = strip_tags(raw)
    attacks = [a for a in actions if a.get('deliveryMethod') == 'ATTACK_ROLL']
    names = sorted({base_name(a['name']) for a in actions}, key=lambda n: (-len(n), n))
    components = []
    for sentence in SENTENCE.split(text):
        if re.search(r'\b(replace|replaced|substitute|in place of|instead|after one of)\b',
                     sentence, re.I):
            continue
        for phrase in re.split(r'[,:;]|\band then\b|\bthen\b|\band\b', sentence):
            lowered = phrase.lower()
            count_match = re.search(r'\b(' + '|'.join(COUNTS) + r')\b', lowered)
            count = COUNTS[count_match.group(1)] if count_match else 1
            found, spans = [], []
            for candidate in names:
                for match in re.finditer(r'\b' + re.escape(candidate.lower()) + r'(?:e?s)?\b', lowered):
                    if any(s <= match.start() < e for s, e in spans):
                        continue
                    spans.append((match.start(), match.end()))
                    found.append((match.start(), candidate))
            if not found:
                for candidate in names:
                    first = candidate.split()[0].lower()
                    unique = sum(1 for n in names if n.split()[0].lower() == first) == 1
                    word = r'\b' + re.escape(first) + r'\b'
                    if len(candidate.split()) > 1 and unique and re.search(word, lowered):
                        found.append((0, candidate))
                        break
            found.sort()
            if not found:
                # "makes three melee attacks", unless the sentence goes on to name them ("four attacks: one with its
                # bite, ...")
                generic = re.search(r'\b(' + '|'.join(COUNTS) + r')\s+(melee |ranged |weapon )?attacks?\b', lowered)
                if generic and not re.search(r':|\busing\b|\bwith (?:its|his|her|their)\b', sentence.lower()) \
                        and not re.search(r'\b(cast|spell)', lowered):
                    kind = (generic.group(2) or '').strip()
                    fitting = [a for a in attacks if not kind or kind.upper() in a.get('attackType', '')]
                    for a in fitting:
                        components.extend([a['name']] * COUNTS[generic.group(1)])
                continue
            for _, candidate in found:
                real = next(a['name'] for a in actions if base_name(a['name']) == candidate)
                components.extend([real] * count)
    return {'name': 'Multiattack', 'actionType': 'ACTION', 'isMultiattack': True,
            'multiattackDescription': text, 'multiattackComponents': components}


def actions_of(monster):
    """The action lists of a stat block in the definitions' format, and the legendary resistance count."""
    result = {}
    entries = [e for e in monster.get('action') or [] if isinstance(e, dict) and e.get('name')]
    built = [build(e, 'ACTION', monster['name']) for e in entries if not e['name'].startswith('Multiattack')]
    built = [b for b in built if b]
    multi = [multiattack(e, built) for e in entries if e['name'].startswith('Multiattack')]
    result['actions'] = multi + built
    for key_5e, key_def, action_type in (('bonus', 'bonusActions', 'BONUS_ACTION'),
                                         ('reaction', 'reactions', 'REACTION')):
        built = [build(e, action_type, monster['name']) for e in monster.get(key_5e) or []]
        built = [b for b in built if b]
        if built:
            result[key_def] = built
    for trait in monster.get('trait') or []:
        match = re.match(r'Legendary Resistance \((\d+)/Day\)', strip_tags(trait.get('name', '')))
        if match:
            result['legendaryResistanceCount'] = int(match.group(1))
            result['traits'] = [{'name': strip_tags(trait['name']), 'isLegendaryResistance': True,
                                 'uses': int(match.group(1)), 'recharge': 'LONG_REST'}]
    return result


def merged(existing, generated):
    """The entry with its generated keys: counts after the challenge rating, lists before the spellcasting."""
    head = ('monsterName', 'source', 'challengeRating')
    entry = {k: existing[k] for k in head if k in existing}
    if 'legendaryResistanceCount' in generated:
        entry['legendaryResistanceCount'] = generated['legendaryResistanceCount']
    for k in ('actions', 'bonusActions', 'reactions', 'traits'):
        if k in generated:
            entry[k] = generated[k]
    for k, v in existing.items():
        if k not in entry and k not in ('actions', 'bonusActions', 'reactions', 'traits', 'legendaryResistanceCount',
                                        MARK):
            entry[k] = v
    entry[MARK] = True
    return entry


def main():
    check = '--check' in sys.argv
    with open(DEFINITIONS, 'rb') as f:
        raw = f.read()
    crlf = b'\r\n' in raw[:200]
    definitions = json.loads(raw.decode('utf-8'))
    targets = {}
    for position, entry in enumerate(definitions):
        if entry.get(MARK) or (entry.get('spellcasting') and set(entry) <= SPELLCASTING_ONLY):
            targets.setdefault(key(entry['monsterName'], entry['source']), position)

    stats = {'monsters': 0, 'actions': 0, 'attacks': 0, 'saves': 0, 'multiattacks': 0, 'multiattacksEmpty': 0,
             'recharge': 0, 'perDay': 0, 'manual': 0, 'bonusActions': 0, 'reactions': 0, 'versatile': 0,
             'legendaryResistance': 0}
    missing = set(targets)
    for path in sorted(glob.glob(BESTIARY)):
        if 'fluff' in os.path.basename(path) or 'index' in os.path.basename(path):
            continue
        with open(path, encoding='utf-8') as f:
            monsters = json.load(f).get('monster', [])
        for monster in monsters:
            k = key(monster.get('name', ''), monster.get('source', ''))
            if '_copy' in monster or k not in missing:
                continue
            missing.discard(k)
            generated = actions_of(monster)
            definitions[targets[k]] = merged(definitions[targets[k]], generated)
            stats['monsters'] += 1
            stats['legendaryResistance'] += 'legendaryResistanceCount' in generated
            for list_key in ('actions', 'bonusActions', 'reactions'):
                for a in generated.get(list_key, []):
                    stats[list_key if list_key != 'actions' else 'actions'] += 1
                    stats['attacks'] += a.get('deliveryMethod') == 'ATTACK_ROLL'
                    stats['saves'] += a.get('deliveryMethod') == 'SAVING_THROW'
                    stats['multiattacks'] += bool(a.get('isMultiattack'))
                    stats['multiattacksEmpty'] += bool(a.get('isMultiattack')) and not a['multiattackComponents']
                    stats['recharge'] += 'recharge' in a
                    stats['perDay'] += 'usesPerDay' in a
                    stats['manual'] += a.get('automatable') is False
                    stats['versatile'] += 'versatile' in a
    print(', '.join('%s %d' % item for item in stats.items()))
    for k in sorted(missing):
        print('  no stat block: ' + k)

    text = json.dumps(definitions, indent=2, ensure_ascii=False)
    if crlf:
        text = text.replace('\n', '\r\n')
    changed = text.encode('utf-8') != raw
    print('would change the file' if check and changed else 'up to date' if not changed else 'written')
    if check:
        sys.exit(1 if changed else 0)
    if changed:
        with open(DEFINITIONS, 'wb') as f:
            f.write(text.encode('utf-8'))


if __name__ == '__main__':
    main()
