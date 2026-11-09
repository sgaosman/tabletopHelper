import { useEffect, useRef } from 'react';
import Modal from '../ui/Modal';
import Button from '../ui/Button';
import type { GlossaryEntry } from '../../types/reference';
import { CATEGORY_LABELS } from '../../utils/glossary';

const PARAGRAPH = 'font-body text-[15px] font-semibold text-muted leading-relaxed';

function RulesText({ text }: { text: string }) {
  return (
    <div className="space-y-2">
      {text.split('\n').filter(line => line.trim()).map((line, i) => (
        <p key={i} className={PARAGRAPH}>{line}</p>
      ))}
    </div>
  );
}

/**
 * A glossary term's definition (M19): its summary for someone new to the game, then the rules text it comes from,
 * folded away under "Full rules text" when there is a summary. "See also" opens a related term in the same dialog.
 * A small centred dialog rather than a hover popover, so a tap opens it and a tap on the backdrop closes it.
 */
export default function GlossaryModal({ entry, related, onSelect, onClose }: {
  entry: GlossaryEntry;
  /** The entry with an ID, for the "See also" buttons */
  related: (id: string) => GlossaryEntry | undefined;
  /** Shows another entry in this dialog */
  onSelect: (id: string) => void;
  onClose: () => void;
}) {
  const contentRef = useRef<HTMLDivElement>(null);
  const shownFirst = useRef(entry.id);

  // After "See also" swaps the entry, focus goes to the new text, as the button pressed may be gone
  useEffect(() => {
    if (entry.id !== shownFirst.current) contentRef.current?.focus();
  }, [entry.id]);

  const seeAlso = entry.seeAlso.map(related).filter((e): e is GlossaryEntry => e !== undefined);

  return (
    <Modal title={entry.name} subtitle={`${CATEGORY_LABELS[entry.category]} · ${entry.source}`} onClose={onClose} size="sm">
      <div ref={contentRef} tabIndex={-1} className="space-y-3 focus:outline-none">
        {entry.summary && <p className="font-body text-[16px] font-semibold text-ink leading-relaxed">{entry.summary}</p>}
        {entry.body && (entry.summary ? (
          <details className="border-t border-rule-light pt-2">
            <summary className="font-heading text-[13px] font-semibold tracking-[0.05em] uppercase text-faint cursor-pointer hover:text-ink">
              Full rules text
            </summary>
            <div className="mt-2"><RulesText text={entry.body} /></div>
          </details>
        ) : <RulesText text={entry.body} />)}
        {seeAlso.length > 0 && (
          <div className="border-t border-rule-light pt-2">
            <h3 className="font-heading text-[13px] font-semibold tracking-[0.05em] uppercase text-faint mb-1.5">See also</h3>
            <div className="flex flex-wrap gap-1.5">
              {seeAlso.map(other => (
                <Button key={other.id} variant="secondary" size="sm" onClick={() => onSelect(other.id)}>{other.name}</Button>
              ))}
            </div>
          </div>
        )}
      </div>
    </Modal>
  );
}
