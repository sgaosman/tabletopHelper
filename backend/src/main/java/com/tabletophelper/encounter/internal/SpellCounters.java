package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.CombatCommand;
import com.tabletophelper.encounter.CombatLog;
import com.tabletophelper.encounter.CombatLogRepository;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.ConcentrationInstance;
import com.tabletophelper.encounter.state.ConditionEntry;
import com.tabletophelper.encounter.state.SpellEffectEntry;
import com.tabletophelper.reference.Spell;
import com.tabletophelper.reference.SpellRepository;
import com.tabletophelper.rules.combat.Resolver;
import com.tabletophelper.shared.ConflictException;
import com.tabletophelper.shared.ValidationException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Counterspell and Dispel Magic (D196), the two spells that end other spells.
 * <p>
 * <b>Counterspell</b> (PHB p.228) is cast as a reaction at the creature casting a spell, after that spell has been
 * cast: the app has no pause in a cast for reactions, so the countered spell is the newest combat command, which must be
 * a cast by the target. A spell of the Counterspell slot's level or lower fails; a higher one fails on a spellcasting
 * ability check against DC 10 + its level. A failed spell is taken back through undo's before-state (D176), and its
 * caster keeps what casting it cost (the slot, the turn's action, sorcery points), as the spell was cast and failed.
 * <p>
 * <b>Dispel Magic</b> (PHB p.234) ends every spell on its target: each of the slot's level or lower ends, and each
 * higher one ends on a spellcasting ability check against DC 10 + its level. A spell is on a creature when it gave the
 * creature a condition or Ongoing Modifiers, or is a lasting spell its caster cast on itself alone.
 */
@Component
@RequiredArgsConstructor
public class SpellCounters {

    public static final String COUNTERSPELL = "Counterspell";
    public static final String DISPEL_MAGIC = "Dispel Magic";

    private final CommandRecorder commandRecorder;
    private final UndoService undoService;
    private final CombatLogRepository combatLogs;
    private final CombatLogWriter combatLog;
    private final SpellRepository spellRepository;
    private final SpellEffects spellEffects;
    private final EffectApplier effectApplier;
    private final CreatureStats stats;
    private final Resolver resolver;

    /** Whether the spell is one this class resolves. */
    public static boolean handles(String spellName) {
        return COUNTERSPELL.equalsIgnoreCase(spellName) || DISPEL_MAGIC.equalsIgnoreCase(spellName);
    }

    /** The spell a Counterspell would counter, found before anything is spent. */
    public record Countered(CombatCommand command, EncounterParticipant caster, String spellName, int level) {
    }

    /**
     * The newest spell cast by the Counterspell's one target, which must be the newest combat command (D196).
     *
     * @throws ConflictException when the newest command isn't a spell that creature cast
     */
    public Countered countered(Encounter encounter, List<EncounterParticipant> targets) {
        if (targets.size() != 1) {
            throw new ValidationException("Counterspell targets the one creature casting a spell");
        }
        EncounterParticipant target = targets.getFirst();
        UUID current = CommandRecorder.current().map(CommandRecorder.Recording::id).orElse(null);
        CombatCommand newest = commandRecorder.undoable(encounter.getId(), 2).stream()
                .filter(command -> !command.getId().equals(current))
                .findFirst()
                .orElseThrow(() -> new ConflictException("There is no spell to counter"));
        String lead = target.getDisplayName() + " casts ";
        CombatLog cast = combatLogs.findByCommandIdOrderByCreatedAtAsc(newest.getId()).stream()
                .filter(entry -> entry.getActionType() == CombatActionType.SPELL_CAST)
                .filter(entry -> target.getId().equals(entry.getActorId()))
                .filter(entry -> entry.getDescription() != null && entry.getDescription().startsWith(lead))
                .findFirst()
                .orElseThrow(() -> new ConflictException("The last action isn't a spell cast by "
                        + target.getDisplayName() + ", so there is nothing to counter"));
        String rest = cast.getDescription().substring(lead.length());
        String spellName = spellName(rest);
        Spell spell = spellRepository.findByNameIgnoreCase(spellName)
                .orElseThrow(() -> new ConflictException("Can't tell which spell " + target.getDisplayName()
                        + " cast"));
        return new Countered(newest, target, spell.getName(), castLevel(rest, spell.getLevel()));
    }

    /**
     * Counters the spell: success at the slot's level or lower, otherwise a spellcasting ability check against DC 10 +
     * the spell's level (PHB p.228). On a success the countered command is taken back, keeping its caster's costs.
     * Called before the Counterspell's own cost is spent, so taking back a spell that changed the counterer can't
     * give back that cost.
     *
     * @return what happened, for the log and the response
     */
    public String counter(Encounter encounter, EncounterParticipant counterer, Countered countered, int slotLevel,
                          String castingAbility, int attackBonus, UUID userId) {
        String spell = countered.spellName() + (countered.level() > 0 ? " (level " + countered.level() + ")" : "");
        String check = "";
        boolean success = countered.level() <= slotLevel;
        if (!success) {
            int modifier = stats.spellcastingModifier(counterer, castingAbility, attackBonus);
            int roll = resolver.d20();
            int dc = 10 + countered.level();
            success = roll + modifier >= dc;
            check = ": spellcasting ability check d20(" + roll + ")" + signed(modifier) + " = " + (roll + modifier)
                    + " vs DC " + dc;
        }
        String text = counterer.getDisplayName() + " counters " + countered.caster().getDisplayName() + "'s " + spell
                + check + (success ? " - the spell fails and has no effect." : " - the spell goes ahead.");
        if (success) {
            EncounterParticipant caster = countered.caster();
            // The failed spell's cost stays spent (PHB p.228: the creature was casting it)
            var slots = caster.getSpellSlotsCurrent() != null
                    ? new LinkedHashMap<>(caster.getSpellSlotsCurrent()) : null;
            var pools = caster.getResourcePoolsCurrent() != null
                    ? new ArrayList<>(caster.getResourcePoolsCurrent()) : null;
            var economy = caster.getTurnEconomy();
            undoService.revert(encounter, countered.command(), userId);
            caster.setSpellSlotsCurrent(slots);
            caster.setResourcePoolsCurrent(pools);
            caster.setTurnEconomy(economy);
        }
        combatLog.save(CombatLogWriter.about(combatLog.entry(encounter, CombatActionType.SPELL_CAST, text),
                        counterer, countered.caster())
                .undoesCommandId(success ? countered.command().getId() : null));
        return text;
    }

    /** A spell on a creature that Dispel Magic can end. */
    public record SpellOn(UUID casterId, String spellName, int level) {
    }

    /** The spells on the creature (PHB p.234), each once, at the level it was cast. */
    public List<SpellOn> spellsOn(Encounter encounter, EncounterParticipant target) {
        Map<String, SpellOn> found = new LinkedHashMap<>();
        for (ConditionEntry condition : target.getActiveConditions() != null ? target.getActiveConditions()
                : List.<ConditionEntry>of()) {
            if (condition == null || condition.sourceSpellName() == null || condition.sourceParticipantId() == null) {
                continue;
            }
            Optional<Spell> spell = spellRepository.findByNameIgnoreCase(condition.sourceSpellName());
            if (spell.isEmpty()) {
                continue;
            }
            EncounterParticipant caster = find(encounter, condition.sourceParticipantId());
                int level = caster != null ? castLevel(caster, spell.get())
                    : spell.get().getLevel() != null ? spell.get().getLevel() : 0;
            found.putIfAbsent(key(condition.sourceParticipantId(), spell.get().getName()),
                    new SpellOn(condition.sourceParticipantId(), spell.get().getName(), level));
        }
        for (EncounterParticipant caster : encounter.getParticipants()) {
            for (SpellEffectEntry effect : EffectApplier.effects(caster)) {
                boolean affected = effect.affected() != null && effect.affected().contains(target.getId());
                boolean onItself = caster.getId().equals(target.getId())
                        && (effect.affected() == null || effect.affected().isEmpty())
                        && spellEffects.selfOnly(effect.spell());
                if (affected || onItself) {
                    int level = effect.slotLevel() != null ? effect.slotLevel()
                            : spellRepository.findByNameIgnoreCase(effect.spell()).map(Spell::getLevel).orElse(0);
                    found.putIfAbsent(key(caster.getId(), effect.spell()),
                            new SpellOn(caster.getId(), effect.spell(), level));
                }
            }
        }
        return List.copyOf(found.values());
    }

    /**
     * Dispels the spells on the target (PHB p.234): each at the slot's level or lower ends, each higher one on a
     * spellcasting ability check against DC 10 + its level.
     *
     * @return what happened, for the log and the response
     */
    public String dispel(Encounter encounter, EffectApplier.Use use, EncounterParticipant dispeller,
                         EncounterParticipant target, int slotLevel, String castingAbility, int attackBonus) {
        List<SpellOn> spells = spellsOn(encounter, target);
        if (spells.isEmpty()) {
            String text = "No spell is on " + target.getDisplayName() + ".";
            effectApplier.summary(encounter, use, text);
            return text;
        }
        List<String> notes = new ArrayList<>();
        int modifier = stats.spellcastingModifier(dispeller, castingAbility, attackBonus);
        for (SpellOn spell : spells) {
            boolean ends = spell.level() <= slotLevel;
            String check = "";
            if (!ends) {
                int roll = resolver.d20();
                int dc = 10 + spell.level();
                ends = roll + modifier >= dc;
                check = " (check d20(" + roll + ")" + signed(modifier) + " = " + (roll + modifier) + " vs DC " + dc
                        + ")";
            }
            String text = spell.spellName() + " (level " + spell.level() + ") on " + target.getDisplayName()
                    + (ends ? " ends" : " holds") + check + ".";
            effectApplier.summary(encounter, use, text);
            notes.add(text);
            if (ends) {
                end(encounter, target, spell);
            }
        }
        return String.join(" ", notes);
    }

    // Ends the spell: its caster's concentration on it, its Spell Effects, and any condition it left on the target
    private void end(Encounter encounter, EncounterParticipant target, SpellOn spell) {
        String why = "Dispel Magic";
        EncounterParticipant caster = find(encounter, spell.casterId());
        if (caster != null) {
            ConcentrationInstance concentration = caster.getConcentration();
            if (concentration != null && spell.spellName().equalsIgnoreCase(concentration.spell())) {
                effectApplier.endConcentration(encounter, caster, why);
            }
            for (SpellEffectEntry effect : EffectApplier.effects(caster)) {
                if (spell.spellName().equalsIgnoreCase(effect.spell())) {
                    effectApplier.endEffect(encounter, caster, effect.id(), why);
                }
            }
        }
        for (ConditionEntry condition : List.copyOf(target.getActiveConditions() != null
                ? target.getActiveConditions() : List.of())) {
            if (condition != null && spell.spellName().equalsIgnoreCase(condition.sourceSpellName())
                    && Objects.equals(spell.casterId(), condition.sourceParticipantId())) {
                effectApplier.endCondition(encounter, target, condition.id(), why);
            }
        }
    }

    // The level the spell was cast at: its caster's concentration on it or its Spell Effect, else the spell's own
    private static int castLevel(EncounterParticipant caster, Spell spell) {
        ConcentrationInstance concentration = caster.getConcentration();
        if (concentration != null && spell.getName().equalsIgnoreCase(concentration.spell())
                && concentration.slotLevel() != null) {
            return concentration.slotLevel();
        }
        return EffectApplier.effects(caster).stream()
                .filter(effect -> spell.getName().equalsIgnoreCase(effect.spell()) && effect.slotLevel() != null)
                .map(SpellEffectEntry::slotLevel)
                .findFirst()
                .orElse(spell.getLevel() != null ? spell.getLevel() : 0);
    }

    // The spell named at the start of a cast's log text: "Fireball at level 4 (reaction).", "Hold Person — ..."
    static String spellName(String rest) {
        int end = rest.length();
        for (String stop : List.of(" at level ", " (", " —", ".", " through ")) {
            int at = rest.indexOf(stop);
            if (at > 0 && at < end) {
                end = at;
            }
        }
        return rest.substring(0, end).trim();
    }

    // The level a cast's log text names ("at level 5"), or the spell's own
    static int castLevel(String rest, Integer spellLevel) {
        String marker = " at level ";
        int at = rest.indexOf(marker);
        if (at >= 0) {
            String digits = rest.substring(at + marker.length()).replaceAll("^(\\d+).*$", "$1");
            try {
                return Integer.parseInt(digits);
            } catch (NumberFormatException ignored) {
                // fall back to the spell's own level
            }
        }
        return spellLevel != null ? spellLevel : 0;
    }

    private static String key(UUID casterId, String spellName) {
        return casterId + "|" + spellName.toLowerCase(Locale.ROOT);
    }

    private static EncounterParticipant find(Encounter encounter, UUID id) {
        return encounter.getParticipants().stream().filter(p -> p.getId().equals(id)).findFirst().orElse(null);
    }

    private static String signed(int value) {
        return value >= 0 ? " + " + value : " - " + (-value);
    }
}
