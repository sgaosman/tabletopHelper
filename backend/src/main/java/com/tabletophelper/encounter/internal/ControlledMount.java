package com.tabletophelper.encounter.internal;

import com.tabletophelper.encounter.CombatActionType;
import com.tabletophelper.encounter.Encounter;
import com.tabletophelper.encounter.EncounterParticipant;
import com.tabletophelper.encounter.state.SummonLink;
import com.tabletophelper.rules.combat.SummonRules;
import com.tabletophelper.shared.ConflictException;

import java.util.UUID;

/**
 * A controlled mount (PHB p.198): "it has only three action options: Dash, Disengage, and Dodge". A Find Steed steed
 * whose initiative follows its rider's, the controlled-mount mode (PHB p.240, D182), is one. Anything else it is asked to
 * do is refused for a player with a 409, and the DM's is applied and logged as the DM's call, as the DM is never refused
 * (D174).
 */
final class ControlledMount {

    private ControlledMount() {
    }

    /** Whether the participant is a steed its rider controls: Find Steed's, following its caster's initiative. */
    static boolean controlled(EncounterParticipant participant) {
        SummonLink link = participant.getSummon();
        return link != null && link.kind() == SummonRules.Kind.STEED && link.followsCaster();
    }

    /**
     * Refuses a player's action other than Dash, Disengage or Dodge by a controlled mount, and logs the DM's.
     *
     * @param what what it was asked to do, such as "use Hooves"
     * @throws ConflictException for a player
     */
    static void requireMountAction(Encounter encounter, EncounterParticipant mount, String what, UUID userId,
                                   CombatLogWriter combatLog) {
        if (!controlled(mount)) {
            return;
        }
        if (!CombatAccess.isDm(encounter, userId)) {
            throw new ConflictException(mount.getDisplayName() + " is a controlled mount, and a controlled mount can "
                    + "only Dash, Disengage or Dodge");
        }
        combatLog.write(encounter, CombatActionType.MONSTER_ACTION, "The DM lets " + mount.getDisplayName() + " "
                + what + ", though a controlled mount can only Dash, Disengage or Dodge", mount, null);
    }
}
