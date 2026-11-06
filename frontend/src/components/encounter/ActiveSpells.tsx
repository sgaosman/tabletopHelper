import { RotateCw } from 'lucide-react';
import type { EncounterParticipant } from '../../types/encounter';
import IconButton from '../ui/IconButton';

/**
 * The spell a participant is concentrating on, and its active spell that needs no concentration. `onRepeat` adds
 * the DM's button to repeat the spell's effect beside each.
 */
export default function ActiveSpells({ participant: p, onRepeat }: {
  participant: EncounterParticipant;
  onRepeat?: () => void;
}) {
  return (
    <>
      {p.concentrationSpell && (
        <div className="flex items-center gap-1.5 mt-0.5">
          <p className="text-cls-warlock font-body text-[15px] font-semibold">Concentrating: {p.concentrationSpell}</p>
          {onRepeat && (
            <IconButton label="Repeat spell effect" onClick={e => { e.stopPropagation(); onRepeat(); }}
              className="p-0.5 bg-cls-warlock/10 hover:bg-cls-warlock/20 text-cls-warlock">
              <RotateCw className="w-3 h-3" />
            </IconButton>
          )}
        </div>
      )}
      {p.activeSpell && (
        <div className="flex items-center gap-1.5 mt-0.5">
          <p className="text-cls-monk font-body text-[15px] font-semibold">Active: {p.activeSpell}</p>
          {onRepeat && (
            <IconButton label="Repeat spell effect" onClick={e => { e.stopPropagation(); onRepeat(); }}
              className="p-0.5 bg-cls-monk/10 hover:bg-cls-monk/20 text-cls-monk">
              <RotateCw className="w-3 h-3" />
            </IconButton>
          )}
        </div>
      )}
    </>
  );
}
