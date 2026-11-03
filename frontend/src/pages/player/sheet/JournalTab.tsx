import { useState } from 'react';
import { useUpdateDetails } from '../../../queries/characters';
import type { CharacterDetailsRequest, PlayerCharacter } from '../../../types/character';
import type { RunCommand } from './types';

type JournalField = 'personalityTraits' | 'ideals' | 'bonds' | 'flaws' | 'notes';

const FIELDS: Array<{ key: JournalField; label: string }> = [
  { key: 'personalityTraits', label: 'Personality Traits' },
  { key: 'ideals', label: 'Ideals' },
  { key: 'bonds', label: 'Bonds' },
  { key: 'flaws', label: 'Flaws' },
  { key: 'notes', label: 'Notes' },
];

/** The character's personality and notes, which its owner edits as details no rule reads (D158). */
export default function JournalTab({ char, runCommand }: {
  char: PlayerCharacter;
  runCommand: RunCommand;
}) {
  const updateDetails = useUpdateDetails();
  const [editing, setEditing] = useState<JournalField | null>(null);
  const [editValue, setEditValue] = useState('');

  async function save(key: JournalField) {
    const details: CharacterDetailsRequest = { [key]: editValue };
    if (await runCommand(updateDetails.mutateAsync({ characterId: char.id, details }))) {
      setEditing(null);
    }
  }

  return (
    <div className="space-y-4">
      {FIELDS.map(({ key, label }) => (
        <div key={key} className="bg-card border border-rule p-4">
          <div className="flex items-center justify-between mb-2">
            <h3 className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-faint">{label}</h3>
            <button
              onClick={() => {
                if (editing === key) { void save(key); }
                else { setEditing(key); setEditValue(char[key] || ''); }
              }}
              disabled={updateDetails.isPending}
              className="font-body text-[14px] font-semibold text-muted hover:text-ink transition-colors disabled:opacity-50"
            >
              {editing === key ? 'Save' : 'Edit'}
            </button>
          </div>
          {editing === key ? (
            <textarea
              aria-label={label}
              value={editValue}
              onChange={e => setEditValue(e.target.value)}
              rows={4}
              className="w-full px-3 py-2 bg-page border border-rule font-body text-[16px] font-semibold text-ink resize-none focus:outline-none focus:border-muted"
              autoFocus
            />
          ) : (
            <p className="font-body text-[15px] font-semibold text-muted whitespace-pre-line">
              {char[key] || <span className="text-faint italic">Not set</span>}
            </p>
          )}
        </div>
      ))}
    </div>
  );
}
