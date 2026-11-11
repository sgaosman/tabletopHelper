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

A hit's riders (D202): the hit's first sentence is its damage, extra damage joined with "plus" (flat amounts such as
"plus 3 lightning damage" too) and alternatives with "or"; extra or alternative damage under a condition the app can
tell (the target prone or of a creature type, the attacker's advantage, form or half hit points) carries an onlyIf,
and an alternative replacesDamage. The rest of that sentence and each later one are clauses: a save for damage
("taking 18 (4d8) poison damage on a failed save, or half as much") gives DAMAGE parts with the save's saveDC,
saveAbility and halfOnSave, which the hit's save guards; a condition with or without a save, in either wording ("be
poisoned", "have the prone condition"), with "until the end of the X's next turn" as endOfUsersTurn and "a Large or
smaller creature" as maxTargetSize. A clause that only applies sometimes ("if", "while", "until this grapple ends",
"reduces ... to 0 hit points") or says more than the parts can is the dmNote's, "Also on a hit, applied by hand: ...".

What lasts beyond a hit (D203) reuses what spells do: "its hit point maximum is reduced by an amount equal to the
damage taken" is HP_MAX_REDUCTION (until a long rest, diesAtZero when "the target dies if this effect reduces its hit
point maximum to 0"), "the vampire regains hit points equal to that amount" LIFE_DRAIN, "gain 1 level of exhaustion"
EXHAUSTION, "can't regain hit points", a speed reduced or halved, disadvantage, advantage against it or no reactions
until a turn's end or start MODIFIER, and damage at the start of each of its turns ONGOING_DAMAGE. A condition or rider
that lasts while another of the hit does carries endsWith ("until this grapple ends, the target is restrained", "while
poisoned in this way, the target can't regain hit points"), and takes that condition's save; "if the target is a
creature other than an undead or a construct" is exemptTypes; a stirge's "attaches to the target" is an attached
condition with its drain at the start of the stirge's turns. The attack keeps how many creatures its grapple holds
(grappleLimit, "the crab has two claws, each of which can grapple only one target") and whether it can't be used on
another meanwhile (grappleLimitBlocks, "the snake can't constrict another target"); a saving throw action keeps the
immunity a success gives (immuneAfterSave) and the creature types it has no effect on (exemptTypes). A rider tied to a
condition the parts don't give goes back to the DM's note.

Then a rider pass gives every hand-built entry's attacks what their stat block's hit gives beyond their own parts,
leaving their attack bonus and damage alone, and marks each change so the next run takes it away first (rider_pass);
its saving throw actions get the immunity and exemptions their stat block gives (D203).

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
# "if used with two hands", and the later wording "when used with two hands to make a melee attack" (D202)
VERSATILE = re.compile(r',?\s*or (\d+) \(\{@damage ([^}]+)\}\) ([a-z]+) damage (?:if|when) (?:used|wielded) with '
                       r'(?:two|both) hands(?: to make a melee attack)?')
SAVE = re.compile(r'\{@dc (\d+)\}\s+(Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma)'
                  r'(?: or (Strength|Dexterity|Constitution|Intelligence|Wisdom|Charisma))?\s+saving throw')
ACT_SAVE = re.compile(r'\{@actSave (str|dex|con|int|wis|cha)\}\s*\{@dc (\d+)\}')
GRAPPLE = re.compile(r'\{@condition grappled\}(?: condition)?\s*\(escape \{@dc (\d+)\}\)')
# A hit that restrains until a Strength check breaks free, as a web does
RESTRAINED = re.compile(r'\{@condition restrained\}[^.]*\.[^.]*?\{@dc (\d+)\} Strength check')
PLAIN_RECHARGE = re.compile(r'[;\s]*Recharge (\d)(?:\s*[\u2013-]\s*6)?')
ATTACK_WORDS = {'mw': 'Melee Weapon Attack:', 'rw': 'Ranged Weapon Attack:', 'mw,rw': 'Melee or Ranged Weapon Attack:',
                'ms': 'Melee Spell Attack:', 'rs': 'Ranged Spell Attack:', 'ms,rs': 'Melee or Ranged Spell Attack:'}
# "or be {@condition poisoned}", "is knocked {@condition prone}", and the later wording "or have the {@condition prone}
# condition" (D202), with any conditions joined to it ("and {@condition X}")
CONDITION = re.compile(r'\b(?:be|become|becomes|is|are|knocked|falls?|(?:has|have|gains?) the)\s+(?:knocked\s+)?'
                       r'\{@condition (\w+)\}((?:,?\s*(?:and|or)?\s*(?:the\s+)?\{@condition \w+\})*)')
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

# ── A hit's riders (D202) ─────────────────────────────────────────────────────────────────────────────────────────────
# A hit's first sentence is its damage, "N (XdY + Z) type damage", then any extra damage joined to it with "plus" or
# an alternative with "or", each possibly with a condition ("if the target is prone"); what follows ("..., and the
# target must succeed on ...") is cut into clauses, as each later sentence is one.
DICE_DAMAGE = r'(\d+)\s*\((?:\{@damage )?(\d+d\d+(?:\s*[+-]\s*\d+)?)\}?\)\s*([a-z]+(?:(?:, or |, | or )[a-z]+)*)\s+damage'
ONE_DAMAGE = re.compile(r'\s*(?:' + DICE_DAMAGE + r'|(\d+)\s+([a-z]+)\s+damage)')
LINK = re.compile(r'\s*,?\s*(plus|or|and)\s+(?:an? (?:extra|additional)\s+)?')
TAIL = re.compile(r'\s+((?:if|while|when|against|to|in)\b[^,;.]*)')
SUBCLAUSE = re.compile(r',?\s+and\s+(?=(?:the target|if|each|it|its|the [a-z]+ (?:must|is|can|has))\b)|;\s+')
SIZES = ('tiny', 'small', 'medium', 'large', 'huge', 'gargantuan')
SIZE_CAP = re.compile(r'\b(tiny|small|medium|large|huge)(?: size)? or smaller\b')
CREATURE_TYPES = ('aberration', 'beast', 'celestial', 'construct', 'dragon', 'elemental', 'fey', 'fiend', 'giant',
                  'humanoid', 'monstrosity', 'ooze', 'plant', 'undead')
TYPE_WORD = r'(?:an? )?(?:%s)s?' % '|'.join(['monstrosities'] + list(CREATURE_TYPES))
TYPE_LIST = TYPE_WORD + r'(?:(?:, or |, | or )' + TYPE_WORD + r')*'
CONDITION_WORD = r'(%s)' % '|'.join(sorted(CONDITIONS - {'invisible'}))
# The qualifiers a clause may carry and still be read: the target being a creature, or no bigger than a size
NEUTRAL = re.compile(r'\bif (?:the target|it) is (?:a creature|(?:an? )?(?:tiny|small|medium|large|huge) or smaller'
                     r'(?: creature| target)?)\b,?|\b(?:tiny|small|medium|large|huge) or smaller\b')
# A clause that only applies sometimes, or is something else (ongoing damage, swallowing), is read by the DM
QUALIFIER = re.compile(r"\b(?:if|while|unless|when|whenever|instead|except|no longer|not|until (?:this|the) (?:grapple|poison|"
                       r"disease|curse|effect)|swallow\w*|engulf\w*|start of each|end of each|each of its turns|"
                       r"reduces?\b[^.]*\bto 0 hit points)\b")
REPEAT_ANY = re.compile(r"(?:(?:the target|a target|it|the creature|a creature|the [a-z]+ target) )?(?:can |must )?repeat(?:s|ing)? "
                        r"the saving throw at the end of each of its turns(?:, ending the (?:effect|poison|condition) "
                        r"on itself on a success)?")
FAILED = re.compile(r'\bon a failed save\b|\bor (?:take|takes)\b|\bor (?:it )?(?:take|takes|suffer)\b')
# The words a read clause may still hold; any other word in it is a rider the DM applies
FILLER = set('''the target target's it is be become becomes has have condition conditions and or a an creature must
succeed on make makes saving throw strength dexterity constitution intelligence wisdom charisma knocked falls fall for
minute minutes hour hours also are taking take takes failed save half as much damage one successful its which then
to this that with'''.split())


def plain(text):
    return strip_tags(text).lower()


def qualified(text):
    """Whether a clause applies only sometimes, or does something the parts can't say."""
    lowered = NEUTRAL.sub(' ', REPEAT_ANY.sub(' ', plain(text)))
    return bool(QUALIFIER.search(lowered))


def unread_words(text):
    """The words of a clause the parts don't say: none when the clause is read in full."""
    lowered = NEUTRAL.sub(' ', REPEAT_ANY.sub(' ', plain(text)))
    lowered = re.sub(r"until the (?:end|start) of (?:its|the target's|(?:the )?[a-z][a-z' -]*?'s) next turn", ' ',
                     lowered)
    lowered = re.sub(r'\d+ \(\d+d\d+(?:\s*[+-]\s*\d+)?\) [a-z]+(?:(?:, or |, | or )[a-z]+)* damage', ' ', lowered)
    lowered = re.sub(r'\(escape dc \d+\)|\bdc \d+', ' ', lowered)
    return [w for w in re.findall(r"[a-z']+", lowered) if w not in FILLER and w not in CONDITIONS]


def size_cap(text):
    """The largest size a clause's condition reaches, "Large" for "a Large or smaller creature"; None for any."""
    match = SIZE_CAP.search(plain(text))
    return match.group(1).capitalize() if match else None


def predicate(tail):
    """What a damage's condition asks, as an onlyIf object, or None when the app can't tell (D202): the target's
    condition or creature type, the attacker's advantage on the roll, its form, or its hit points at half or fewer."""
    text = plain(tail).strip().rstrip('.').strip()
    if re.search(r'\bor if\b|\bwithin\b|\bmoved?\b|\bstraight\b', text):
        return None
    when = None
    if re.fullmatch(r"(?:if|when|while) (?:[a-z' -]+ )?(?:has|had) advantage on (?:the|its|this|that) attack roll"
                    r"(?: and hits(?: the target)?)?|if the attack roll had advantage", text):
        when = {'attackerAdvantage': True}
    condition = (re.fullmatch(r"(?:if|while) the target (?:is|has the) (?:already )?" + CONDITION_WORD
                              + r"(?: condition)?( by (?:the )?[a-z' -]+)?", text)
                 or re.fullmatch(r"against (?:a |an |any )?" + CONDITION_WORD + r" (?:target|creature)s?()", text))
    if condition:
        when = {'targetCondition': condition.group(1)}
        if condition.group(2):
            when['byAttacker'] = True
    types = re.fullmatch(r'(?:if the target is |against |to )(' + TYPE_LIST + ')', text)
    if types:
        found = [t for t in CREATURE_TYPES if re.search(r'\b' + t, types.group(1))]
        if 'monstrosities' in types.group(1):
            found.append('monstrosity')
        when = {'targetType': sorted(set(found))}
    if re.fullmatch(r"while (?:(?:it is|(?:the )?[a-z' -]+ is) )?enlarged|while under the effect of enlarge", text):
        when = {'attackerForm': 'enlarged'}
    form = re.fullmatch(r'(?:while )?in ([a-z]+(?: [a-z]+)?) form', text)
    if form and ' or ' not in form.group(1):
        when = {'attackerForm': form.group(1) + ' form'}
    if re.fullmatch(r"while (?:(?:it is|(?:the )?[a-z' -]+ is) )?raging", text):
        when = {'attackerForm': 'raging'}
    if re.fullmatch(r"if the [a-z' -]+ (?:has|is at) half (?:of )?its hit points(?: \(\d+\))? or fewer", text):
        when = {'attackerBloodied': True}
    if when:
        when['text'] = text
    return when


def damage_part(match):
    """A DAMAGE part of a ONE_DAMAGE match, and whether its type is a choice."""
    if match.group(2):
        damage_type, choice = match.group(3), False
        if ' or ' in damage_type or ',' in damage_type:
            choice = True
            damage_type = damage_type.split(',')[0].split(' or ')[0]
        return {'effectType': 'DAMAGE', 'damageDice': match.group(2).strip(), 'damageType': damage_type}, choice
    return {'effectType': 'DAMAGE', 'damageDice': match.group(4), 'damageType': match.group(5),
            'flatDamage': True}, False


def base_damage(first):
    """A hit's damage from the start of its first sentence: the parts, whether a choice is left to the DM, and the rest
    of the sentence. Extra damage that only applies sometimes carries an onlyIf, and an alternative that replaces the
    first damage also replacesDamage; one whose condition the app can't tell is the DM's (D202)."""
    match = ONE_DAMAGE.match(first)
    if not match:
        return [], False, first
    part, choice = damage_part(match)
    parts, position = [part], match.end()
    # Damage joined with "and" to extra damage ("plus 10 (3d6) necrotic damage and 10 (3d6) poison damage") is one
    # group with it, and a condition after the last holds for the whole group: where it starts in parts, and the text
    group = None
    while True:
        link = LINK.match(first, position)
        found = ONE_DAMAGE.match(first, link.end()) if link else None
        if not found:
            break
        kind = link.group(1)
        if kind == 'and' and group is None:
            break
        part, typed_choice = damage_part(found)
        end = found.end()
        tail = TAIL.match(first, end)
        when = predicate(tail.group(1)) if tail else None
        if tail and not when:
            if kind == 'or':
                # An alternative the app can't tell apart ("at range"), so the DM settles it
                choice, position, group = True, tail.end(), None
                continue
            if kind == 'and':
                # "plus 4 piercing damage and 9 (2d8) cold damage if the javelin was used ...": all of it the DM's
                del parts[group[0]:]
                position = group[1]
            break
        if tail:
            end = tail.end()
        if kind == 'or':
            if not when:
                # "or 16 (2d12 + 3) piercing damage plus ..." with no condition: the DM's choice, and the rest with it
                choice, position = True, len(first)
                break
            part['onlyIf'] = when
            part['replacesDamage'] = True
            group = None
        else:
            if kind == 'plus':
                group = (len(parts), position)
            if when:
                part['onlyIf'] = when
                for joined in parts[group[0]:]:
                    joined.setdefault('onlyIf', dict(when))
        choice = choice or typed_choice
        parts.append(part)
        position = end
    return parts, choice, first[position:]


# "If the darkling has advantage on the attack roll, the attack deals an extra 7 (2d6) piercing damage." and the
# like, with the damage's condition before it or after it
EXTRA_SENTENCE = re.compile(r"^(?:if (?P<pre>[^,]+?),?\s+)?(?:(?:the attack|it|the target|(?:the |this )?[a-z' -]+?) "
                            r"(?:deals|takes|suffers)\s+)?an? (?:extra|additional)\s+(?P<dmg>" + DICE_DAMAGE + r")"
                            r"(?:\s+is dealt to the target|\s+to the target)?(?:\s+(?P<post>(?:if|when|while|against)\b"
                            r".+?))?\.?$")


def extra_sentence(sentence):
    """The conditional extra damage a later sentence of a hit gives, as one DAMAGE part with onlyIf, or None."""
    match = EXTRA_SENTENCE.match(plain(sentence))
    if not match or bool(match.group('pre')) == bool(match.group('post')):
        return None
    when = predicate('if ' + match.group('pre') if match.group('pre') else match.group('post'))
    if not when:
        return None
    part, choice = damage_part(ONE_DAMAGE.match(match.group('dmg')))
    if choice:
        return None
    part['onlyIf'] = when
    return part


def read_clause(clause, following):
    """The parts a clause of a hit gives, and whether that is all it says (D202): a grapple, a condition with or
    without a save, and damage a failed save deals; a size limit is kept on each condition as maxTargetSize. A
    clause that only applies sometimes still gives its grapple, as before, and the rest is the DM's."""
    found = saves(clause)
    if len(found) > 1 or (found and found[0][2]):
        return [], False
    cap = size_cap(clause)
    parts = []
    grapple = GRAPPLE.search(clause)
    if grapple:
        part = {'effectType': 'CONDITION', 'condition': 'grappled', 'escapeDC': int(grapple.group(1))}
        if cap:
            part['maxTargetSize'] = cap
        parts.append(part)
    # A clause about a save made in another, "On a failure, the target is poisoned", is that save's, which the DM
    # applies
    if qualified(clause) or not found and re.search(r'\bfail|\bsucce', plain(clause)):
        return parts, False
    damage = list(DAMAGE.finditer(clause))
    if found:
        dc, ability = found[0][:2]
        if damage:
            if not FAILED.search(plain(clause)):
                return parts, False
            half = bool(re.search(r'half as much|half damage', plain(clause)))
            for match in damage:
                part, choice = damage_part(ONE_DAMAGE.match(match.group(0)))
                if choice:
                    return parts, False
                part.update({'saveDC': dc, 'saveAbility': ability, 'halfOnSave': half})
                parts.append(part)
        given = [c for c in conditions(clause, (dc, ability)) if c['condition'] != 'grappled']
        # How long they last and any save to end them can be said in the next sentences
        later = conditions(' '.join([clause, following]), (dc, ability))
        for c in given:
            c.update({k: v for k, v in next(x for x in later if x['condition'] == c['condition']).items()
                      if k not in c})
    else:
        if damage:
            return parts, False
        given = [c for c in conditions(clause) if c['condition'] != 'grappled']
    for c in given:
        if cap:
            c['maxTargetSize'] = cap
        parts.append(c)
    return parts, bool(parts) and not unread_words(clause)


# ── Lasting riders, linked conditions and grapple limits (D203) ──────────────────────────────────────────────────────
# A hit's clauses that reuse what spells do: a hit point maximum reduced by the damage (HP_MAX_REDUCTION), life drain
# (LIFE_DRAIN), exhaustion (EXHAUSTION), an Ongoing Modifier (MODIFIER: can't regain hit points, speed, disadvantage,
# no reactions), and damage at the start of each turn (ONGOING_DAMAGE). A condition that lasts while another of the
# hit does carries endsWith ("until this grapple ends, the target is restrained", "until this poison ends, the target
# is paralyzed"); a part that passes over some creature types carries exemptTypes ("if the target is a creature other
# than an undead or a construct"); and the attack keeps how many creatures its grapple holds (grappleLimit) and whether
# it can't be used on another meanwhile (grappleLimitBlocks). A saving throw action keeps the immunity a success gives
# (immuneAfterSave) and the creature types it has no effect on (exemptTypes).
NAME = r"(?:the )?[a-z][a-z'-]*(?: [a-z'-]+){0,4}?"
DICE = r'\d+ \((\d+d\d+(?:\s*[+-]\s*\d+)?)\)'
ABILITY = r'(strength|dexterity|constitution|intelligence|wisdom|charisma)'
SAVE_OR = r'(?:(?:the target|it|the creature|a creature) must succeed on a dc (\d+) ' + ABILITY + r' saving throw,? or )?'
WHO = r"(?:the target|it|the creature|a creature|the target's|its|the creature's)"
UNTIL = (r"(?: (until the (?:end|start) of (?:its|the target's|the creature's|" + NAME + r"'s) next turn|for 1 minute|"
         r"until (?:it|the target|the creature) finishes a (?:long|short or long) rest))?")
TYPES_RE = r'(?:an? )?(?:%s|elf|elves|monstrosities)s?' % '|'.join(CREATURE_TYPES)
EXEMPTION = re.compile(r'^(?:if the target is (?:a creature other than|not) (' + TYPES_RE + r'(?:(?:, or |, | or | and )'
                       + TYPES_RE + r')*)),?\s*', re.I)
COUNT_WORDS = {'one': 1, 'two': 2, 'three': 3, 'four': 4, 'five': 5, 'six': 6, 'seven': 7, 'eight': 8, 'nine': 9,
               'ten': 10, 'only one': 1}


def norm(clause):
    """A clause as the readers below match it: plain, lower-cased, without a leading "and", "if the target is a
    creature," or full stop."""
    text = re.sub(r'^[\s,;]*(?:and\s+)?', '', plain(clause)).strip().rstrip('.').strip()
    return re.sub(r'^if the target is a creature,\s*', '', text)


def exemption(clause):
    """The creature types a clause passes over ("If the target is a creature other than an elf or undead, ..."), and
    the clause without them, its tags kept; none and the clause as it was when it names none."""
    raw = re.sub(r'^[\s,;]*(?:and\s+)?', '', clause)
    match = EXEMPTION.match(raw)
    if not match:
        return [], clause
    rest = raw[match.end():]
    return types_in(match.group(1).lower()), rest[:1].upper() + rest[1:]


def until_of(text):
    """The end a lasting rider's words give it: until the end or start of the target's or the user's next turn, for a
    minute, or until a rest; {} for none."""
    if not text:
        return {}
    if text == 'for 1 minute':
        return {'durationRounds': 10}
    if 'short or long rest' in text:
        return {'until': 'SHORT_REST'}
    if 'long rest' in text:
        return {'until': 'LONG_REST'}
    edge = 'END' if 'until the end' in text else 'START'
    whose = 'TARGET' if re.search(r"of (?:its|the target's|the creature's) next turn", text) else 'USER'
    return {'until': '%s_TURN_%s' % (whose, edge)}


def save_keys(match_dc, match_ability):
    return {'saveDC': int(match_dc), 'saveAbility': ABILITIES[match_ability]} if match_dc else {}


# One Ongoing Modifier a hit leaves, as its words say it, each with the kind it is
MODIFIER_WORDS = [
    (re.compile(WHO + r" speed is reduced by (\d+) feet"), lambda m: {'kind': 'SPEED', 'flat': -int(m.group(1))}),
    (re.compile(WHO + r" speed is halved"), lambda m: {'kind': 'SPEED_HALVED'}),
    (re.compile(WHO + r" speed (?:becomes|is reduced to|is) 0"), lambda m: {'kind': 'SPEED_MAX', 'flat': 0}),
    (re.compile(r"(?:(?:the target|it|the creature|a creature) )?(?:can't|be unable to|is unable to) take reactions"),
     lambda m: {'kind': 'NO_REACTIONS'}),
    (re.compile(r"(?:(?:the target|it|the creature|a creature) )?can't regain hit points"),
     lambda m: {'kind': 'NO_HEALING'}),
    (re.compile(r"(?:(?:the target|it|the creature) )?(?:has|have) disadvantage on attack rolls"),
     lambda m: {'kind': 'ATTACK_MODE', 'mode': 'DISADVANTAGE'}),
    (re.compile(r"(?:(?:the target|it|the creature) )?(?:has|have) disadvantage on (?:all )?(?:(strength|dexterity|"
                r"constitution|intelligence|wisdom|charisma) )?saving throws"),
     lambda m: dict({'kind': 'SAVE_MODE', 'mode': 'DISADVANTAGE'},
                    **({'ability': ABILITIES[m.group(1)]} if m.group(1) else {}))),
    (re.compile(r"(?:(?:the target|it|the creature) )?(?:has|have) disadvantage on (?:(strength|dexterity|"
                r"constitution|intelligence|wisdom|charisma) checks|ability checks)"),
     lambda m: dict({'kind': 'CHECK_MODE', 'mode': 'DISADVANTAGE'},
                    **({'ability': ABILITIES[m.group(1)]} if m.group(1) else {}))),
    (re.compile(r"attack rolls (?:made )?against (?:it|the target|the creature) have advantage"),
     lambda m: {'kind': 'ATTACKED_MODE', 'mode': 'ADVANTAGE'}),
]


def modifier_part(text):
    """A MODIFIER part for one Ongoing Modifier's words, or None."""
    for pattern, make in MODIFIER_WORDS:
        match = pattern.fullmatch(text)
        if match:
            return {'effectType': 'MODIFIER', 'modifier': make(match)}
    return None


def modifiers_of(text):
    """The MODIFIER parts of words joined with "and" or commas ("its speed is halved, and it can't take reactions"),
    or None when any of them isn't one."""
    pieces = [p for p in re.split(r',\s*and\s+|,\s*|\s+and\s+', text) if p]
    parts = [modifier_part(p) for p in pieces]
    return parts if parts and all(parts) else None


HP_MAX = re.compile(SAVE_OR + r"(?:(?:the target's|its|the creature's) hit point maximum is (?:also )?reduced|"
                    r"(?:have|has) its hit point maximum reduced) by (?:an amount equal to (half )?(?:the )?"
                    r"(?:(acid|cold|fire|lightning|necrotic|poison|psychic|radiant|thunder|force) )?damage "
                    r"(?:taken|dealt|the target takes)|" + DICE + r")"
                    r"(?:,? and (" + NAME + r") (?:magically )?regains hit points equal to (?:that amount|the damage "
                    r"(?:dealt|taken)))?")
HP_MAX_LASTS = re.compile(r"(?:this|the) reduction(?: to (?:the target's|its|a creature's|the creature's) hit point "
                          r"maximum)? lasts until (?:the target|the creature|it) finishes a (long|short or long) rest")
HP_MAX_DIES = re.compile(r"(?:the (?:target|creature) dies if (?:this effect|this attack|the reduction|this|it) "
                         r"reduces its hit point maximum to 0|the (?:target|creature) dies if its hit point maximum "
                         r"is reduced to 0|if this (?:effect|attack) reduces (?:a creature's|the target's|its) hit "
                         r"point maximum to 0, the (?:target|creature) dies)")
DRAIN = re.compile(r"(" + NAME + r") (?:magically )?(regains hit points|gains temporary hit points) equal to (half )?"
                   r"(?:the (?:amount of )?|that amount)(?:(acid|cold|fire|lightning|necrotic|poison|psychic|radiant|"
                   r"thunder|force) )?(?:damage (?:dealt|taken|the target takes))?(?: if the target is a creature)?")
EXHAUST = re.compile(SAVE_OR + r"(?:gain|gains|suffer|suffers) (1|one|a|2|two) levels? of exhaustion")
MODIFIER_CLAUSE = re.compile(SAVE_OR + r"(.+?)" + UNTIL)
WHILE_POISONED = re.compile(r"(?:while poisoned in this way|until this poison ends),? (?:the target|a creature|it) "
                            r"(?:is (?:also )?|has the )(paralyzed|unconscious|incapacitated|stunned|restrained)"
                            r"(?: condition)?(?:,? and (can't regain hit points))?")
POISONED_TAIL = re.compile(r"(?:the target|it) is (?:also )?(paralyzed|unconscious|incapacitated) while poisoned in "
                           r"this way")
WHILE_POISONED_RIDERS = re.compile(r"while poisoned in this way, (?:the target|a creature|it) (.+)")
GRAPPLE_RESTRAINS = re.compile(r"(?:(?:until (?:this|the) grapple ends|until the grappled condition ends|while "
                               r"grappled(?: in this way)?),? (?:the target|the creature|it) (?:is|has the|also has "
                               r"the) (restrained|blinded|frightened|incapacitated)(?: condition)?|(?:it|the target) is "
                               r"(restrained) until (?:this|the) grapple ends)(.*)")
GRAPPLED_AND = re.compile(r"(.*?\(escape dc \d+\)) and (is restrained|has the restrained condition|takes " + DICE
                          + r" ([a-z]+) damage at the start of each of its turns) until (?:this|the) grapple ends")
GRAPPLE_DAMAGE = re.compile(r"(?:until (?:this|the) grapple ends,? )?(?:the target|the creature|it) takes " + DICE
                            + r" ([a-z]+) damage at the start of each of (its|" + NAME + r"'s) turns(?: until "
                            r"(?:this|the) grapple ends)?(.*)")
CANT_OTHER = re.compile(r"(?:(?:until (?:this|the) grapple ends|while (?:the )?[a-z' -]+ is grappling (?:a|the) "
                        r"(?:target|creature)),? )?(?:" + NAME + r"|it) can't (?:constrict|bite|use (?:its|the same|"
                        r"this) [a-z ]+?(?: attack)? (?:on|against)|attack) (?:another|other) (?:target|creature)s?"
                        r"(?: with (?:its|his|her|their) [a-z ]+)?")
LIMIT = re.compile(r"(?:" + NAME + r"|it) can (?:grapple|constrain) (?:only one|one|(?:no more than|up to) (two|three|"
                   r"four|five|six)) (?:target|creature)s?(?: (?:with|using) (?:its|his|her|the) [a-z' ]+?)?"
                   r"(?: at a time)?(?: (?:with|using) (?:its|his|her|the) [a-z' ]+)?|(?:" + NAME + r") can have "
                   r"(?:only one|up to (two|three|four)) (?:creature|target)s? grappled(?: (?:in )?this way)?"
                   r"(?: at a time)?")
LIMBS = re.compile(r"(?:" + NAME + r") has (two|three|four|five|six|seven|eight|nine|ten) ([a-z]+)(?:, each of which "
                   r"can grapple (?:only )?one (?:target|creature))?")
NOT_ALREADY = re.compile(r",? if (?:" + NAME + r") (?:isn't|is not) already (?:grappling|constricting) (?:a|another) "
                         r"(?:creature|target)")
NOT_ALREADY_RAW = re.compile(NOT_ALREADY.pattern, re.I)
ATTACHES = re.compile(r"(" + NAME + r") attaches (?:itself )?to (?:the target|it)")
ATTACHED_DRAIN = re.compile(r"(?:instead, )?at the start of each of (" + NAME + r")'s turns, the target (?:loses "
                            + DICE + r" hit points(?: due to blood loss)?|takes " + DICE + r" ([a-z]+) damage)")
TURN_LOSS = re.compile(SAVE_OR + r"(?:(?:the target|it|the creature) )?(?:lose|loses) " + DICE + r" hit points at the start of each of its turns"
                       r"(?: due to an? [a-z ]+)?")
TURN_TAKE = re.compile(SAVE_OR + r"(?:(?:the target|it|the creature) )?(?:take|takes) " + DICE + r" ([a-z]+) damage at the start of each of its turns"
                       r"(?: due to an? [a-z ]+)?")
DETACH = re.compile(r"(?:as an action, )?a creature(?: within reach of [a-z' -]+)?(?:, including the target,)? can "
                    r"(?:try to |take its action to |use its action to )?detach (?:it|[a-z' -]+?)(?:,? doing so with a "
                    r"successful| by (?:making a successful|succeeding on a)) dc (\d+) strength check(?: as an action)?")
DRAINS_TOTAL = re.compile(r"it does so after it drains (\d+) hit points of blood from the target or the target dies")
IMMUNE = re.compile(r"(?:is|are) immune to (?:the |this |that )?[a-z' -]*?(?:'s )?[a-z' -]+ (for (?:the next )?24 hours"
                    r"|for (?:the next )?(?:1 hour|hour)|until (?:it|the creature) finishes a long rest)")
NO_EFFECT_ON = re.compile(r"(?:this [a-z ]+ has no effect on|(?:it|they) (?:have|has) no effect on) (" + TYPES_RE
                          + r"(?:(?:, or |, | or | and )" + TYPES_RE + r")*)|(" + TYPES_RE + r"(?:(?:, or |, | or | and )"
                          + TYPES_RE + r")*) are immune to this effect")


def types_in(text):
    found = []
    for word in re.findall(r'[a-z]+', text):
        word = {'elves': 'elf', 'monstrosities': 'monstrosity'}.get(word, word)
        if word.endswith('s') and word[:-1] in CREATURE_TYPES:
            word = word[:-1]
        if word in CREATURE_TYPES or word == 'elf':
            found.append(word)
    return sorted(set(found))


def last(parts, effect_type, **match):
    for part in reversed(parts):
        if part.get('effectType') == effect_type and all(part.get(k) == v for k, v in match.items()):
            return part
    return None


def read_more(clause, effects, conditions_read, action_keys, previous=None):
    """What a clause of a hit says beyond read_clause (D203): new parts, and whether that is all it says; None when it
    is none of these. Continuation clauses change the parts already read ("This reduction lasts until ...", "The target
    dies if ...", "It does so after it drains 10 hit points ..."), and the attack's grapple limit goes in
    action_keys. previous is the part read last, which a clause going on from it ("..., and it takes ...") follows."""
    text = norm(clause)
    built = effects + conditions_read
    if not text:
        return None
    # Hit point maximum reduced, and life drain equal to that amount
    match = HP_MAX.fullmatch(text)
    if match:
        dc, ability, half, kind, dice, drainer = match.groups()
        part = dict({'effectType': 'HP_MAX_REDUCTION', 'until': 'LONG_REST'}, **save_keys(dc, ability))
        if dice:
            part['damageDice'] = dice.strip()
        if kind:
            part['damageType'] = kind
        if half:
            part['half'] = True
        parts = [part]
        if drainer:
            parts.append(dict({'effectType': 'LIFE_DRAIN'}, **({'damageType': kind} if kind else {}),
                              **save_keys(dc, ability)))
        return parts, True
    match = HP_MAX_LASTS.fullmatch(text)
    if match and last(built, 'HP_MAX_REDUCTION'):
        last(built, 'HP_MAX_REDUCTION')['until'] = 'SHORT_REST' if 'short' in match.group(1) else 'LONG_REST'
        return [], True
    if HP_MAX_DIES.fullmatch(text) and last(built, 'HP_MAX_REDUCTION'):
        last(built, 'HP_MAX_REDUCTION')['diesAtZero'] = True
        return [], True
    if re.fullmatch(r"the reduction lasts until the target finishes a long rest\. the target dies if its hit point "
                    r"maximum is reduced to 0", text) and last(built, 'HP_MAX_REDUCTION'):
        last(built, 'HP_MAX_REDUCTION').update({'until': 'LONG_REST', 'diesAtZero': True})
        return [], True
    match = DRAIN.fullmatch(text)
    if match:
        part = {'effectType': 'LIFE_DRAIN'}
        if 'temporary' in match.group(2):
            part['tempHp'] = True
        if match.group(3):
            part['half'] = True
        reduced = last(built, 'HP_MAX_REDUCTION') if 'that amount' in text else None
        kind = match.group(4) or (reduced or {}).get('damageType')
        if kind:
            part['damageType'] = kind
        if reduced is not None:
            part.update({k: reduced[k] for k in ('saveDC', 'saveAbility') if k in reduced})
        return [part], True
    match = EXHAUST.fullmatch(text)
    if match:
        dc, ability, count = match.groups()
        return [dict({'effectType': 'EXHAUSTION', 'levels': 2 if count in ('2', 'two') else 1},
                     **save_keys(dc, ability))], True
    # A condition that lasts while the poisoning does, with what else it brings meanwhile
    match = WHILE_POISONED.fullmatch(text) or POISONED_TAIL.fullmatch(text)
    if match:
        parts = [{'effectType': 'CONDITION', 'condition': match.group(1), 'endsWith': 'poisoned'}]
        if match.re is WHILE_POISONED and match.group(2):
            parts.append({'effectType': 'MODIFIER', 'modifier': {'kind': 'NO_HEALING'}, 'endsWith': 'poisoned'})
        return inherit(parts, built, 'poisoned'), True
    match = WHILE_POISONED_RIDERS.fullmatch(text)
    if match and last(built, 'CONDITION', condition='poisoned') is not None:
        parts = linked_riders(match.group(1), 'poisoned')
        if parts:
            return inherit(parts, built, 'poisoned'), True
    # A restraint while grappled, with damage each turn and the attack's limit after it
    match = GRAPPLE_RESTRAINS.fullmatch(text)
    if match:
        parts = [{'effectType': 'CONDITION', 'condition': match.group(1) or match.group(2), 'endsWith': 'grappled'}]
        rest = match.group(3).strip(' ,')
        if rest:
            more = grapple_tail(rest, action_keys)
            if more is None:
                return parts, False
            parts.extend(more)
        return parts, True
    match = GRAPPLE_DAMAGE.fullmatch(text)
    if match and (text.startswith('until') or 'until' in text):
        parts = [{'effectType': 'ONGOING_DAMAGE', 'damageDice': match.group(1).strip(), 'damageType': match.group(2),
                  'endsWith': 'grappled'}]
        if match.group(3) != 'its':
            parts[0]['turnOf'] = 'USER'
        rest = match.group(4).strip(' ,')
        if rest:
            more = grapple_tail(rest, action_keys)
            if more is None:
                return parts, False
            parts.extend(more)
        return parts, True
    # The attack's grapple limit
    if CANT_OTHER.fullmatch(text):
        action_keys['grappleLimitBlocks'] = True
        action_keys.setdefault('grappleLimit', 1)
        return [], True
    match = LIMIT.fullmatch(text)
    if match:
        count = match.group(1) or match.group(2)
        action_keys['grappleLimit'] = COUNT_WORDS.get(count, 1) if count else 1
        return [], True
    match = LIMBS.fullmatch(text)
    if match and ('grapple' in text or match.group(2) in ('tentacles', 'claws', 'arms', 'tendrils', 'vines')):
        action_keys['grappleLimit'] = COUNT_WORDS[match.group(1)]
        return [], True
    # Attached, with its drain at the start of the attacker's turns
    match = ATTACHES.fullmatch(text)
    if match:
        return [{'effectType': 'CONDITION', 'condition': 'attached'}], True
    match = ATTACHED_DRAIN.fullmatch(text)
    if match and last(built, 'CONDITION', condition='attached') is not None:
        dice = match.group(2) or match.group(3)
        part = {'effectType': 'ONGOING_DAMAGE', 'damageDice': dice.strip(), 'turnOf': 'USER', 'endsWith': 'attached'}
        if match.group(4):
            part['damageType'] = match.group(4)
        return [part], True
    match = DRAINS_TOTAL.fullmatch(text)
    if match and last(built, 'ONGOING_DAMAGE', endsWith='attached') is not None:
        last(built, 'ONGOING_DAMAGE', endsWith='attached')['total'] = int(match.group(1))
        return [], True
    # Detaching it with a Strength check made as an action, which the attached creature can roll
    match = DETACH.fullmatch(text)
    if match and last(built, 'CONDITION', condition='attached') is not None:
        last(built, 'CONDITION', condition='attached')['escapeDC'] = int(match.group(1))
        return [], True
    # Damage at the start of each of its turns until the DM ends it, as an infernal wound
    match = TURN_LOSS.fullmatch(text) or TURN_TAKE.fullmatch(text)
    if match:
        dc, ability, dice = match.groups()[:3]
        part = dict({'effectType': 'ONGOING_DAMAGE', 'damageDice': dice.strip()}, **save_keys(dc, ability))
        if match.re is TURN_TAKE:
            part['damageType'] = match.group(4)
        # "..., and it takes 21 (6d6) poison damage at the start of each of its turns" goes on from the clause before,
        # lasting while the same condition does
        if not dc and text.startswith('it ') and isinstance(previous, dict) and previous.get('endsWith'):
            part.update({k: previous[k] for k in ('endsWith', 'saveDC', 'saveAbility') if k in previous})
        elif not dc:
            # Damage each turn with neither a save nor an end it lasts while is the DM's: its end is in words the
            # parts don't say
            return None
        return [part], True
    # An Ongoing Modifier for a while, with or without a save
    match = MODIFIER_CLAUSE.fullmatch(text)
    if match and match.group(4):
        dc, ability, words, until = match.groups()
        parts = modifiers_of(words)
        if parts:
            for part in parts:
                part.update(until_of(until))
                part.update(save_keys(dc, ability))
            return parts, True
    return None


def linked_riders(words, anchor):
    """The riders of words that last while a condition does: "can't regain hit points, and it takes 7 (2d6) poison
    damage at the start of each of its turns", or None when any piece isn't one."""
    parts = []
    for piece in re.split(r',?\s+and\s+(?:it\s+)?|,\s+', words):
        piece = piece.strip()
        if not piece:
            continue
        damage = re.fullmatch(r'(?:it )?takes ' + DICE + r' ([a-z]+) damage at the start of each of its turns', piece)
        condition = re.fullmatch(r'(?:has the|is) (paralyzed|unconscious|incapacitated|stunned|restrained)'
                                 r'(?: condition)?', piece)
        modifier = modifier_part(piece) or modifier_part('it ' + piece)
        if damage:
            parts.append({'effectType': 'ONGOING_DAMAGE', 'damageDice': damage.group(1).strip(),
                          'damageType': damage.group(2), 'endsWith': anchor})
        elif condition:
            parts.append({'effectType': 'CONDITION', 'condition': condition.group(1), 'endsWith': anchor})
        elif modifier:
            modifier['endsWith'] = anchor
            parts.append(modifier)
        else:
            return None
    return parts or None


def inherit(parts, built, anchor):
    """Parts that last while a condition does take that condition's save, so a success keeps them away as well."""
    held = last(built, 'CONDITION', condition=anchor)
    if held is not None and 'saveDC' in held:
        for part in parts:
            part.setdefault('saveDC', held['saveDC'])
            part.setdefault('saveAbility', held['saveAbility'])
    return parts


def grapple_tail(rest, action_keys):
    """What follows "until this grapple ends, the target is restrained": damage each turn and the attack's limit; None
    when it says more."""
    parts = []
    for piece in re.split(r',?\s+and\s+', rest):
        piece = re.sub(r'^and\s+', '', piece.strip(' ,'))
        if not piece:
            continue
        damage = re.fullmatch(r'(?:it )?takes ' + DICE + r' ([a-z]+) damage at the start of each of its turns', piece)
        if damage:
            parts.append({'effectType': 'ONGOING_DAMAGE', 'damageDice': damage.group(1).strip(),
                          'damageType': damage.group(2), 'endsWith': 'grappled'})
        elif CANT_OTHER.fullmatch(piece):
            action_keys['grappleLimitBlocks'] = True
            action_keys.setdefault('grappleLimit', 1)
        else:
            return None
    return parts


def grapple_clause(clause, action_keys):
    """A grapple clause that also restrains or deals damage until the grapple ends ("it is grappled (escape DC 16) and
    is restrained until this grapple ends"), or holds only while the attacker grapples no one else ("if the devil
    isn't already grappling a creature"): the clause to read for the grapple, and the parts the rest gives."""
    raw = clause
    extra = []
    if NOT_ALREADY.search(plain(raw)):
        action_keys.setdefault('grappleLimit', 1)
        raw = NOT_ALREADY_RAW.sub('', raw)
    match = GRAPPLED_AND.fullmatch(plain(raw).strip().rstrip('.'))
    if match:
        if match.group(2).startswith('takes'):
            extra.append({'effectType': 'ONGOING_DAMAGE', 'damageDice': match.group(3).strip(),
                          'damageType': match.group(4), 'endsWith': 'grappled'})
        else:
            extra.append({'effectType': 'CONDITION', 'condition': 'restrained', 'endsWith': 'grappled'})
        # The grapple's own words, which read_clause reads
        cut = re.search(r'\(escape \{@dc \d+\}\)', raw)
        if cut:
            raw = raw[:cut.end()] + '.'
    return raw, extra


def save_riders(raw):
    """The action keys a saving throw action's text gives (D203): how long a success makes a creature immune to it,
    and the creature types it has no effect on."""
    keys = {}
    text = plain(raw)
    match = IMMUNE.search(text)
    if match and re.search(r'success|succeeds|successful', text):
        keys['immuneAfterSave'] = match.group(1)
    match = NO_EFFECT_ON.search(text)
    if match:
        found = types_in(match.group(1) or match.group(2))
        if found:
            keys['exemptTypes'] = found
    return keys


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
    """How long a condition lasts, in rounds and words, from the sentence that gives it, and whether it ends at the end
    of the user's next turn ("until the end of the nereid's next turn"), not the target's (D202)."""
    lowered = text.lower()
    if re.search(r'for 1 minute', lowered):
        return 10, '1 minute', False
    if re.search(r'for 1 hour', lowered):
        return 600, '1 hour', False
    if re.search(r"until the (?:end|start) of (?:its|(?:the|that|each) (?:target|creature)'s) next turn", lowered):
        return 1, 'until the end of its next turn', False
    users = re.search(r"until the (end|start) of ((?:the )?[a-z][a-z' -]*?'s) next turn", strip_tags(text).lower())
    if users:
        # The start of the user's next turn is where a condition's rounds are counted down anyway; its end needs the
        # end-of-turn rule Stunning Strike's stun uses (PHB p.79)
        return 1, 'until the %s of %s next turn' % users.groups(), users.group(1) == 'end'
    return None, None, False


def conditions(text, save=None):
    """The conditions a sentence gives, each a CONDITION part; a save given here is kept on each."""
    found = []
    for match in CONDITION.finditer(text):
        for name in [match.group(1)] + JOINED.findall(match.group(2) or ''):
            name = name.lower()
            if name in CONDITIONS and name not in [f['condition'] for f in found]:
                found.append({'effectType': 'CONDITION', 'condition': name})
    rounds, words, users_turn = duration(text)
    repeat = bool(REPEAT.search(text))
    for part in found:
        if save:
            part['saveDC'], part['saveAbility'] = save
        if words:
            part['durationText'] = words
            part['durationRounds'] = rounds
        if users_turn:
            part['endOfUsersTurn'] = True
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

    reading = read_hit(hit)
    if reading['versatile']:
        action['versatile'] = reading['versatile']
    # What is tied to a condition the hit doesn't give is the DM's (D203)
    names = {p['condition'] for p in reading['effects'] if p['effectType'] == 'CONDITION'}
    dropped, clauses = unanchored(reading, names)
    reading['leftover'] = reading['leftover'] + clauses
    effects = [p for p in reading['effects'] if not any(p is d for d in dropped)]
    if not effects:
        return manual(action, description, "The hit's effect is not damage the app can roll: " + describe(hit))
    action['effects'] = effects
    action.update(reading['action'])
    note = hit_note(reading)
    if note:
        action['dmNote'] = note
    action['description'] = description
    return action


def read_hit(hit):
    """What a hit's text gives (D202): its effects, whether a choice is left to the DM, its first sentence, the clauses
    the effects don't say, and its versatile damage."""
    sentences = SENTENCE.split(hit.strip())
    first, rest = sentences[0], sentences[1:]
    versatile = VERSATILE.search(first)
    two_handed = None
    if versatile:
        two_handed = {'twoHandedDamageDice': versatile.group(2).strip(), 'twoHandedDamageType': versatile.group(3)}
        first = first[:versatile.start()] + first[versatile.end():]
    effects, choice, remainder = base_damage(first)
    if not effects and DAMAGE.search(first) and not saves(first) and not qualified(first):
        # A first sentence whose damage isn't at its start or lacks a word ("The target takes 55 (10d10) piercing
        # damage", "5 (1d4 + 3) plus 10 (3d6) poison damage") is read as D194 read it
        effects, choice = damage_parts(first)
        remainder = ''
    # The first sentence's clauses after its damage, then each later sentence's
    clauses = [(c, True) for c in SUBCLAUSE.split(remainder) if c and c.strip(' .,;')]
    for sentence in rest:
        clauses.extend((c, False) for c in SUBCLAUSE.split(sentence) if c and c.strip(' .,;'))
    conditions_read, leftover = [], []
    # The attack's grapple limit, which the action keeps (D203), and the part read last
    action_keys = {}
    previous = None
    # Each part read with the clause it came from, so one tied to a condition no part gives goes back to the DM
    sources = []
    # A hit with a list of effects to choose from ("one of the following") leaves them all to the DM
    options = bool(OPTIONS.search(hit))
    for index, (clause, in_first) in enumerate(clauses):
        if options:
            leftover.append(clause)
            continue
        following = ' '.join(c for c, _ in clauses[index + 1:])
        extra = None if in_first else extra_sentence(clause)
        if extra:
            effects.append(extra)
            continue
        # "The target can repeat the saving throw ..." once a condition it ends has been read
        if REPEAT_ANY.fullmatch(plain(clause).strip(' .'))                 and any(c.get('saveToEndEachTurn') for c in conditions_read):
            continue
        # The creature types the clause passes over, then what it says that lasts or ties to another condition (D203)
        types, readable = exemption(clause)
        more = read_more(readable, effects, conditions_read, action_keys, previous)
        if more is not None:
            parts, read = more
        else:
            grapple_extra = []
            if GRAPPLE.search(readable):
                readable, grapple_extra = grapple_clause(readable, action_keys)
            parts, read = read_clause(readable, following)
            parts = parts + grapple_extra
        for part in parts:
            previous = part
            sources.append((part, clause))
            if types and part['effectType'] != 'DAMAGE':
                part['exemptTypes'] = types
            if part['effectType'] != 'CONDITION':
                effects.append(part)
                continue
            same = next((c for c in conditions_read if c['condition'] == part['condition']), None)
            if same is None:
                conditions_read.append(part)
            else:
                for k, v in part.items():
                    same.setdefault(k, v)
        # A clause of a first sentence left as a choice is already in the choice's note
        if not read and not (in_first and choice):
            leftover.append(clause)
    effects.extend(conditions_read)
    restrained = RESTRAINED.search(hit)
    if restrained:
        # A web restrains until a Strength check breaks it, as the escape DC the log shows
        held = next((c for c in conditions_read if c['condition'] == 'restrained'), None)
        if held is not None and 'escapeDC' not in held and 'saveDC' not in held:
            held['escapeDC'] = int(restrained.group(1))
        elif not effects:
            effects.append({'effectType': 'CONDITION', 'condition': 'restrained',
                            'escapeDC': int(restrained.group(1))})
    return {'effects': effects, 'choice': choice, 'first': first, 'leftover': leftover, 'versatile': two_handed,
            'action': action_keys, 'sources': sources}


def unanchored(reading, names):
    """The parts read that last while a condition none of the parts gives, as a restraint "until the grapple ends" of a
    grapple the parts don't read ("grappled (DC 24)"), and their clauses, which go back to the DM (D203)."""
    dropped, clauses = [], []
    for part, clause in reading['sources']:
        if part.get('endsWith') and part['endsWith'] not in names:
            dropped.append(part)
            if clause not in clauses and clause not in reading['leftover']:
                clauses.append(clause)
    return dropped, clauses


def hit_note(reading, leftover=None):
    """The dmNote of a hit: its choice, and the clauses the DM applies by hand."""
    notes = []
    if reading['choice']:
        notes.append('On a hit: ' + strip_tags(reading['first']) + ' The DM settles the choice by hand.')
    leftover_text = ' '.join(sentence_of(c) for c in (reading['leftover'] if leftover is None else leftover))
    if leftover_text:
        notes.append(HAND_NOTE + leftover_text)
    return ' '.join(notes)


HAND_NOTE = 'Also on a hit, applied by hand: '


def sentence_of(clause):
    """A clause as a sentence of its own: capitalised, ending with a full stop."""
    text = re.sub(r'^and\s+', '', strip_tags(clause).strip(' ,;'))
    text = text[:1].upper() + text[1:]
    return text if text.endswith(('.', '!', '?')) else text + '.'


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
    # The immunity a success gives, and the creature types the action passes over (D203)
    action.update(save_riders(raw))
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


# ── The rider pass over the hand-built entries (D202) ─────────────────────────────────────────────────────────────────
# A hand-built entry keeps its own attack bonus, damage and parts; each of its attacks matched by name to its stat block
# gets what read_hit derives beyond them, marked so a later run takes it away first and adds it again:
#   - a part the pass adds has "_rider": "added";
#   - a key it adds to a part the entry had lists in that part's "_riderKeys", or "_riderNulls" when it replaced a null;
#   - a part it takes away is kept in the action's "_riderRemoved" with its place;
#   - what it adds to the dmNote is the action's "_riderNote", with "_riderNoteNew" when there was no dmNote;
#   - a key it adds to the action itself, as a grapple limit or a save's immunity (D203), lists in "_riderActionKeys".
RIDER_LISTS = (('action', 'actions', 'ACTION'), ('bonus', 'bonusActions', 'BONUS_ACTION'),
               ('reaction', 'reactions', 'REACTION'), ('legendary', 'legendaryActions', 'LEGENDARY'),
               ('mythic', 'mythicActions', 'MYTHIC'))
DAMAGE_RIDER_KEYS = ('saveDC', 'saveAbility', 'halfOnSave', 'onlyIf', 'replacesDamage')
RIDER_STATS = ('attacks matched', 'attacks changed', 'damage given a save', 'damage given a condition',
               'damage added', 'base damage restored', 'flat damage added', 'conditions added', 'conditions given a save',
               'conditions given a size limit', 'conditions given a duration or end', 'conditions removed',
               'dmNotes added', 'dmNotes extended', 'lasting riders added', 'conditions linked',
               'conditions given an exemption', 'turn damage moved off the hit', 'grapple limits added',
               'save actions matched', 'save actions given an immunity or exemption')
# The keys of a condition that tie it to another or keep it from some creatures (D203)
LINK_KEYS = ('endsWith', 'exemptTypes', 'endsOnDamage')
LASTING_TYPES = ('HP_MAX_REDUCTION', 'LIFE_DRAIN', 'EXHAUSTION', 'MODIFIER', 'ONGOING_DAMAGE')
ACTION_KEYS = ('grappleLimit', 'grappleLimitBlocks', 'immuneAfterSave', 'exemptTypes')


def strip_riders(action):
    """The action as the hand-built data had it, before any rider pass."""
    effects = [p for p in action.get('effects') or [] if not (isinstance(p, dict) and p.get('_rider'))]
    for part in effects:
        if not isinstance(part, dict):
            continue
        for k in part.pop('_riderKeys', []):
            part.pop(k, None)
        for k in part.pop('_riderNulls', []):
            part[k] = None
    for removed in sorted(action.pop('_riderRemoved', []), key=lambda r: r['at']):
        effects.insert(removed['at'], removed['part'])
    if 'effects' in action:
        action['effects'] = effects
    note = action.pop('_riderNote', None)
    if note is not None:
        if action.pop('_riderNoteNew', False):
            action.pop('dmNote', None)
        elif action.get('dmNote', '').endswith(' ' + note):
            action['dmNote'] = action['dmNote'][:-len(note) - 1]
    for k in action.pop('_riderActionKeys', []):
        action.pop(k, None)


def add_action_keys(action, keys):
    """Adds the action-level keys the action lacks, marking each (D203); whether any was added."""
    added = False
    for k, v in keys.items():
        if k in action:
            continue
        action[k] = v
        action.setdefault('_riderActionKeys', []).append(k)
        added = True
    return added


def add_keys(part, keys):
    """Adds the keys a part lacks, marking each; whether any was added."""
    added = False
    for k, v in keys.items():
        if part.get(k) is not None:
            continue
        if k in part:
            part.setdefault('_riderNulls', []).append(k)
        else:
            part.setdefault('_riderKeys', []).append(k)
        part[k] = v
        added = True
    return added


def dice_key(part):
    return re.sub(r'\s+', '', str(part.get('damageDice') or '')).lower(), (part.get('damageType') or '').lower()


def covered(clause, held):
    """Whether a clause only gives conditions the entry gives already, such as "Until this grapple ends, the target is
    restrained" beside a hand-built restraint."""
    names = [n.lower() for n in JOINED.findall(clause)]
    if not names or not all(n in held for n in names):
        return False
    return not unread_words(re.sub(r'until this grapple ends,?', ' ', plain(clause)))


def add_riders(action, reading, stats):
    """Adds to a hand-built attack what its stat block's hit gives beyond its own parts (D202). Damage its stat block
    deals only on a failed save or under a condition gets that save or onlyIf, rather than coming with every hit; a
    condition gets the save, size limit, duration and end its stat block gives when the entry has none; a condition
    the stat block gives only at 0 hit points is taken away; anything else the stat block's hit says goes in the
    dmNote."""
    effects = action['effects']
    original = [p for p in effects if isinstance(p, dict)]
    before = json.dumps(action, sort_keys=True)
    # What is tied to a condition neither the entry nor its stat block gives is the DM's (D203)
    names = {(p.get('condition') or '').lower() for p in original} | {p['condition'] for p in reading['effects']
                                                                         if p['effectType'] == 'CONDITION'}
    dropped, clauses = unanchored(reading, names)
    reading = dict(reading, leftover=reading['leftover'] + clauses)
    built = [p for p in reading['effects'] if not any(p is d for d in dropped)]
    for part in (p for p in built if p['effectType'] == 'DAMAGE' and ('saveDC' in p or 'onlyIf' in p)):
        keys = {k: part[k] for k in DAMAGE_RIDER_KEYS if k in part}
        same = next((p for p in original if p.get('effectType') == 'DAMAGE' and dice_key(p) == dice_key(part)
                     and not any(p.get(k) is not None for k in DAMAGE_RIDER_KEYS)), None)
        if same is not None:
            add_keys(same, keys)
            stats['damage given a save' if 'saveDC' in part else 'damage given a condition'] += 1
        else:
            effects.append(dict(part, _rider='added'))
            stats['damage added'] += 1
    # A hit whose every damage now needs a save or a condition lost its own damage, as the scorpion's 1 piercing
    if not any(p.get('effectType') == 'DAMAGE' and not any(p.get(k) is not None for k in DAMAGE_RIDER_KEYS)
               for p in effects):
        for part in (p for p in built if p['effectType'] == 'DAMAGE' and 'saveDC' not in p and 'onlyIf' not in p):
            effects.append(dict(part, _rider='added'))
            stats['base damage restored'] += 1
    # Flat extra damage of a type the entry deals none of, as "plus 3 lightning damage", which it didn't read
    for part in (p for p in built if p['effectType'] == 'DAMAGE' and p.get('flatDamage')
                 and 'saveDC' not in p and 'onlyIf' not in p):
        if not any(p.get('effectType') == 'DAMAGE' and dice_key(p)[1] == part['damageType'] for p in effects):
            effects.append(dict(part, _rider='added'))
            stats['flat damage added'] += 1
    action_save = action.get('saveDC') is not None or action.get('secondarySaveDC') is not None
    for part in (p for p in built if p['effectType'] == 'CONDITION'):
        same = next((p for p in original if p.get('effectType') == 'CONDITION'
                     and (p.get('condition') or '').lower() == part['condition']), None)
        if same is None:
            effects.append(dict(part, _rider='added'))
            stats['conditions added'] += 1
            continue
        if 'saveDC' in part and same.get('saveDC') is None and not action_save:
            if add_keys(same, {'saveDC': part['saveDC'], 'saveAbility': part['saveAbility']}):
                stats['conditions given a save'] += 1
        if 'maxTargetSize' in part and add_keys(same, {'maxTargetSize': part['maxTargetSize']}):
            stats['conditions given a size limit'] += 1
        ends = {}
        if same.get('durationRounds') is None and 'durationRounds' in part:
            ends.update({k: part[k] for k in ('durationRounds', 'durationText', 'endOfUsersTurn') if k in part})
        if not same.get('saveToEndEachTurn') and part.get('saveToEndEachTurn'):
            ends.update({k: part[k] for k in ('saveToEndEachTurn', 'saveToEndDC', 'saveToEndAbility')})
        if same.get('escapeDC') is None and 'escapeDC' in part:
            ends['escapeDC'] = part['escapeDC']
        if ends and add_keys(same, ends):
            stats['conditions given a duration or end'] += 1
        # Lasting while another condition does, ending on damage, passing over some creature types (D203)
        if 'endsWith' in part and add_keys(same, {k: part[k] for k in ('endsWith',) + (('saveDC', 'saveAbility')
                                                  if 'saveDC' in part and not action_save else ()) if k in part}):
            stats['conditions linked'] += 1
        if 'exemptTypes' in part and add_keys(same, {'exemptTypes': part['exemptTypes']}):
            stats['conditions given an exemption'] += 1
        if part.get('endsOnDamage'):
            add_keys(same, {'endsOnDamage': True})
    # What lasts beyond the hit (D203): added as the stat block gives it, beside any the entry has
    for part in (p for p in built if p['effectType'] in LASTING_TYPES):
        if any(p.get('effectType') == part['effectType'] and p.get('modifier') == part.get('modifier')
               for p in original):
            continue
        effects.append(dict(part, _rider='added'))
        stats['lasting riders added'] += 1
        # Damage each turn the entry dealt on the hit instead, as a chain devil's 2d6 piercing, comes off the hit
        if part['effectType'] == 'ONGOING_DAMAGE':
            hit = {dice_key(p) for p in built if p['effectType'] == 'DAMAGE'}
            for index, other in reversed(list(enumerate(effects))):
                if any(other is o for o in original) and other.get('effectType') == 'DAMAGE' \
                        and dice_key(other) == dice_key(part) and dice_key(other) not in hit \
                        and not any(other.get(k) is not None for k in DAMAGE_RIDER_KEYS):
                    action.setdefault('_riderRemoved', []).insert(0, {'at': index, 'part': other})
                    del effects[index]
                    stats['turn damage moved off the hit'] += 1
                    break
    if reading['action'] and add_action_keys(action, reading['action']):
        stats['grapple limits added'] += 1
    # A condition the stat block gives only once its damage drops the target to 0 hit points, as the phase spider's
    # poisoning, doesn't come with a hit
    built_names = {p['condition'] for p in built if p['effectType'] == 'CONDITION'}
    for index, part in reversed(list(enumerate(effects))):
        name = (part.get('condition') or '').lower() if isinstance(part, dict) else ''
        if any(part is o for o in original) and part.get('effectType') == 'CONDITION' and name not in built_names:
            mentions = [c for c in reading['leftover'] if '{@condition %s}' % name in c.lower()]
            if mentions and all('0 hit points' in plain(c) for c in mentions):
                action.setdefault('_riderRemoved', []).insert(0, {'at': index, 'part': part})
                del effects[index]
                stats['conditions removed'] += 1
    held = {(p.get('condition') or '').lower() for p in effects if isinstance(p, dict)}
    note = hit_note(reading, [c for c in reading['leftover'] if not covered(c, held)])
    if note and note not in (action.get('dmNote') or ''):
        action['_riderNote'] = note
        if action.get('dmNote'):
            action['dmNote'] = action['dmNote'] + ' ' + note
            stats['dmNotes extended'] += 1
        else:
            action['_riderNoteNew'] = True
            action['dmNote'] = note
            stats['dmNotes added'] += 1
    stats['attacks changed'] += json.dumps(action, sort_keys=True) != before


def singular(name):
    lowered = base_name(name).lower()
    return lowered[:-3] + 'f' if lowered.endswith('ves') else lowered[:-1] if lowered.endswith('s') else lowered


def hand_built(actions, name):
    """The hand-built action of a stat-block name: the same name, or else the only one alike but for a plural or a
    note in brackets ("Claws" for "Claw")."""
    for action in actions:
        if action.get('name') == name:
            return action
    alike = [a for a in actions if a.get('name') and singular(a['name']) == singular(name)]
    return alike[0] if len(alike) == 1 else None


def save_pass(actions, stat, raw, stats):
    """A hand-built saving throw action gets the immunity its stat block's success gives and the creature types it
    has no effect on (D203)."""
    action = hand_built(actions, parse_name(stat['name'])[0])
    if action is None or action.get('deliveryMethod') != 'SAVING_THROW' or action.get('automatable') is False:
        return
    stats['save actions matched'] += 1
    keys = save_riders(raw)
    if keys and add_action_keys(action, keys):
        stats['save actions given an immunity or exemption'] += 1


def rider_pass(entry, monster, stats):
    """The rider pass over one hand-built entry and its stat block."""
    for key_5e, key_def, action_type in RIDER_LISTS:
        actions = [a for a in entry.get(key_def) or [] if isinstance(a, dict)]
        for action in actions:
            strip_riders(action)
        for stat in monster.get(key_5e) or []:
            if not isinstance(stat, dict) or not stat.get('name') or stat['name'].startswith('Multiattack'):
                continue
            raw = flatten(stat.get('entries'))
            if not ATK.search(raw) and saves(raw):
                save_pass(actions, stat, raw, stats)
                continue
            if not ATK.search(raw) or '{@h}' not in raw:
                continue
            action = hand_built(actions, parse_name(stat['name'])[0])
            if action is None or action.get('deliveryMethod') != 'ATTACK_ROLL' or action.get('automatable') is False \
                    or not isinstance(action.get('effects'), list) or not HIT.search(raw.partition('{@h}')[0]):
                continue
            stats['attacks matched'] += 1
            add_riders(action, read_hit(raw.partition('{@h}')[2]), stats)


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
    blocks = {}
    for path in sorted(glob.glob(BESTIARY)):
        if 'fluff' in os.path.basename(path) or 'index' in os.path.basename(path):
            continue
        with open(path, encoding='utf-8') as f:
            monsters = json.load(f).get('monster', [])
        for monster in monsters:
            k = key(monster.get('name', ''), monster.get('source', ''))
            if '_copy' not in monster:
                blocks.setdefault(k, monster)
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

    # The rider pass over every other entry with a stat block (D202)
    rider_stats = dict.fromkeys(RIDER_STATS, 0)
    for position, entry in enumerate(definitions):
        k = key(entry['monsterName'], entry['source'])
        if targets.get(k) != position and k in blocks:
            rider_pass(entry, blocks[k], rider_stats)
    print('riders: ' + ', '.join('%s %d' % item for item in rider_stats.items()))

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
