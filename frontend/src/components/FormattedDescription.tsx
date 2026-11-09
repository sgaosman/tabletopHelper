import { Fragment, useMemo } from 'react';
import { useGlossary } from '../hooks/useGlossary';
import { BOLD_PATTERNS, cleanMarkup, splitAtMatches } from '../utils/formatText';
import GlossaryTerm from './glossary/GlossaryTerm';

interface Props {
  text: string;
  className?: string;
}

/**
 * A description as paragraphs, with its game words in bold. Inside a GlossaryProvider (M19) a bold word the Glossary
 * defines, and any condition, sense, skill, action or damage type it finds, is a button that opens the definition;
 * other bold words, such as dice, stay plain bold.
 */
export default function FormattedDescription({ text, className = '' }: Props) {
  const glossary = useGlossary();
  // The Glossary's own patterns go first, so "temporary hit points" wins over "hit points"
  const patterns = useMemo(() => glossary ? [...glossary.patterns, ...BOLD_PATTERNS] : BOLD_PATTERNS, [glossary]);

  if (!text) return null;

  const cleaned = cleanMarkup(text);
  const paragraphs = cleaned.split('\n').filter(p => p.trim().length > 0);

  return (
    <div className={`space-y-2.5 ${className}`}>
      {paragraphs.map((para, i) => {
        const parts = splitAtMatches(para.trim(), patterns);
        return (
          <p key={i} className="text-muted font-body text-[15px] font-semibold leading-relaxed">
            {parts.map((part, j) => {
              if (typeof part === 'string') return <Fragment key={j}>{part}</Fragment>;
              const entry = glossary?.findTerm(part.bold);
              return entry
                ? <GlossaryTerm key={j} entry={entry}>{part.bold}</GlossaryTerm>
                : <strong key={j} className="text-ink font-bold">{part.bold}</strong>;
            })}
          </p>
        );
      })}
    </div>
  );
}
