import { Fragment, type ReactNode } from 'react';
import { useGlossary } from '../../hooks/useGlossary';
import type { GlossaryContextType } from '../../context/glossary';
import { splitAtMatches } from '../../utils/formatText';
import { parseMarkupSegments } from '../../utils/parseMarkup';
import GlossaryTerm from './GlossaryTerm';

/** A run of text with its glossary terms as links. */
function linked(text: string, glossary: GlossaryContextType): ReactNode[] {
  return splitAtMatches(text, glossary.patterns).map((part, i) => {
    if (typeof part === 'string') return <Fragment key={i}>{part}</Fragment>;
    const entry = glossary.findTerm(part.bold);
    return entry
      ? <GlossaryTerm key={i} entry={entry} bold={false}>{part.bold}</GlossaryTerm>
      : <Fragment key={i}>{part.bold}</Fragment>;
  });
}

/**
 * Text with its 5etools tags converted as parseMarkup converts them, such as {@h} to an italic "Hit:" and
 * {@recharge 5} to "(Recharge 5-6)", and, inside a GlossaryProvider, its glossary terms as links (M19). It has none
 * of FormattedDescription's bolding or paragraphs, for text laid out by its own component, such as a monster's stat
 * block. It renders React elements, never HTML.
 */
export default function GlossaryText({ text, className }: { text: string; className?: string }) {
  const glossary = useGlossary();
  return (
    <span className={className}>
      {parseMarkupSegments(text).map((segment, i) => {
        const content = glossary ? linked(segment.text, glossary) : segment.text;
        if (segment.style === 'bold') return <strong key={i}>{content}</strong>;
        if (segment.style === 'italic') return <em key={i}>{content}</em>;
        return <Fragment key={i}>{content}</Fragment>;
      })}
    </span>
  );
}
