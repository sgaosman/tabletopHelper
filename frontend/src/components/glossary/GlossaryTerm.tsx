import type { ReactNode } from 'react';
import { useGlossary } from '../../hooks/useGlossary';
import type { GlossaryEntry } from '../../types/reference';

/**
 * A game term that opens its glossary definition (M19): a button that reads as part of the sentence, in the ink
 * colour with a dotted underline, so it keeps the text's contrast in every theme (D173). Render it only inside a
 * GlossaryProvider.
 */
export default function GlossaryTerm({ entry, bold = true, children }: {
  entry: GlossaryEntry;
  /** False where the surrounding text isn't bold, such as a stat block */
  bold?: boolean;
  children: ReactNode;
}) {
  const glossary = useGlossary();
  return (
    <button
      type="button"
      aria-haspopup="dialog"
      title={`What is ${entry.name}?`}
      onClick={e => {
        // A term inside a clickable card or row opens only the definition
        e.stopPropagation();
        glossary?.openTerm(entry.id);
      }}
      className={`${bold ? 'font-bold' : ''} text-ink underline decoration-dotted decoration-faint decoration-1 underline-offset-[3px] hover:decoration-ink hover:decoration-solid cursor-help text-left`}
    >
      {children}
    </button>
  );
}
