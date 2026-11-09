import { useState } from 'react';
import { apiErrorMessage } from '../../api/errors';
import type { CombatLogEntry, UndoInfo, UndoableCommand } from '../../types/combat';
import Button from '../ui/Button';
import Modal from '../ui/Modal';

interface Props {
  /** What the DM View offers to undo */
  undo: UndoInfo;
  /** How many of the newest commands to undo: 1 for Undo, more for "Undo to here" */
  steps: number;
  /** The DM's combat log, whose entries each command wrote are listed under it */
  log: CombatLogEntry[];
  /** Undoes the commands, naming the newest as the dialog showed it */
  onUndo: (expectedCommandId: string, steps: number) => Promise<unknown>;
  onClose: () => void;
}

function CommandRow({ command, entries }: { command: UndoableCommand; entries: CombatLogEntry[] }) {
  return (
    <li className="bg-page-alt border border-rule px-3 py-2">
      <p className="font-heading text-[15px] font-bold text-ink">{command.summary}</p>
      {entries.length > 0 ? (
        <ul className="mt-1 space-y-0.5">
          {entries.map(entry => (
            <li key={entry.id} className="font-body text-[14px] font-semibold text-muted">{entry.description}</li>
          ))}
        </ul>
      ) : (
        <p className="mt-1 font-body text-[14px] font-semibold text-faint">Wrote nothing to the log</p>
      )}
    </li>
  );
}

/**
 * The DM's Undo dialog (M14). It lists each combat command the undo takes back, newest first, with the log entries it
 * wrote, and says that dice are rolled afresh if the action is taken again. Undoing Next Turn gives back the turn,
 * the round, the budget, the conditions that ended and the recharges, which Previous Turn doesn't. A player acting
 * while the dialog is open makes the server refuse the undo, and the dialog says so.
 */
export default function UndoDialog({ undo, steps, log, onUndo, onClose }: Props) {
  const [undoing, setUndoing] = useState(false);
  const [error, setError] = useState('');
  const commands = undo.recent.slice(0, steps);
  const undoesTurn = commands.some(command => command.kind === 'TURN_ADVANCE');

  async function handleUndo() {
    setUndoing(true);
    setError('');
    try {
      await onUndo(undo.commandId, commands.length);
    } catch (err: unknown) {
      setError(apiErrorMessage(err));
      setUndoing(false);
      return;
    }
    onClose();
  }

  return (
    <Modal
      title={commands.length === 1 ? 'Undo the last action?' : `Undo the last ${commands.length} actions?`}
      onClose={onClose}
      dismissible={!undoing}
      footer={(
        <>
          <Button variant="secondary" onClick={onClose} disabled={undoing}>Cancel</Button>
          <Button variant="danger" onClick={handleUndo} loading={undoing}>
            {undoing ? 'Undoing...' : 'Undo'}
          </Button>
        </>
      )}
    >
      <div className="space-y-4">
        <section aria-label="What will be undone">
          <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint mb-2">
            What will be undone, newest first
          </h3>
          <ul className="space-y-1.5">
            {commands.map(command => (
              <CommandRow key={command.commandId} command={command}
                entries={log.filter(entry => entry.commandId === command.commandId)} />
            ))}
          </ul>
        </section>

        {undoesTurn && (
          <p className="font-body text-[14px] font-semibold text-muted">
            Undoing Next Turn gives back the turn, the round, the turn&apos;s budget, the conditions that ended and the
            recharges rolled. Previous Turn only moves the turn marker.
          </p>
        )}
        {commands.length >= undo.stepsAvailable && (
          <p className="font-body text-[14px] font-semibold text-muted">
            This is as far back as undo goes: nothing before the encounter&apos;s start, or before a creature was
            removed mid-fight, can be undone.
          </p>
        )}
        <p className="font-body text-[14px] font-semibold text-muted">
          Everything goes back to how it was before. Dice aren&apos;t undone: if the action is taken again, its dice
          are rolled fresh. Undo can&apos;t be undone.
        </p>

        {error && <p role="alert" className="text-debuff font-body text-[15px]">{error}</p>}
      </div>
    </Modal>
  );
}
