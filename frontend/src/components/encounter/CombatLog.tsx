import { useEffect, useRef, useState, type ReactNode } from 'react';
import { ChevronLeft, ChevronRight, ScrollText } from 'lucide-react';
import type { CombatLogEntry } from '../../types/combat';
import { isTurnChange, logBorderColour, logTextColour } from '../../utils/combatLogStyle';

/**
 * The combat log, folded until opened, grouped by round and turn, and kept at the newest entry unless the reader has
 * scrolled up. `entries` is whatever the server sent for this view: the players' log leaves out entries about hidden
 * participants (D157, D171). `entryActions` adds controls after an entry, such as the DM's Legendary Resistance.
 */
export default function CombatLog({ entries, entryActions }: {
  entries: CombatLogEntry[];
  entryActions?: (entry: CombatLogEntry) => ReactNode;
}) {
  const [expanded, setExpanded] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [isAtBottom, setIsAtBottom] = useState(true);
  const [newCount, setNewCount] = useState(0);
  const prevLogCount = useRef(0);
  const isAtBottomRef = useRef(true);

  useEffect(() => { isAtBottomRef.current = isAtBottom; }, [isAtBottom]);

  useEffect(() => {
    const added = entries.length - prevLogCount.current;
    if (prevLogCount.current === 0 && entries.length > 0) {
      if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    } else if (added > 0) {
      if (isAtBottomRef.current && scrollRef.current) {
        scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
      } else {
        setNewCount(prev => prev + added);
      }
    }
    prevLogCount.current = entries.length;
  }, [entries]);

  function handleScroll() {
    if (!scrollRef.current) return;
    const { scrollTop, scrollHeight, clientHeight } = scrollRef.current;
    const atBottom = scrollHeight - scrollTop - clientHeight < 30;
    setIsAtBottom(atBottom);
    if (atBottom) setNewCount(0);
  }

  function scrollToBottom() {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
      setIsAtBottom(true);
      setNewCount(0);
    }
  }

  return (
    <div className="bg-card border border-rule overflow-hidden">
      <button
        onClick={() => setExpanded(!expanded)}
        className="w-full flex items-center justify-between px-4 py-3 hover:bg-page-alt"
      >
        <div className="flex items-center gap-2">
          <ScrollText className="w-4 h-4 text-muted" />
          <span className="font-heading text-[15px] font-semibold tracking-[0.01em] text-muted">Combat Log</span>
          <span className="font-body text-[15px] font-semibold text-faint">({entries.length} entries)</span>
        </div>
        <ChevronRight className={`w-4 h-4 text-faint transition-transform ${expanded ? 'rotate-90' : ''}`} />
      </button>
      {expanded && (
        <div className="relative">
          <div ref={scrollRef} onScroll={handleScroll} aria-live="polite" aria-label="Combat log entries" className="max-h-64 overflow-y-auto border-t border-rule px-4 py-2 space-y-1">
            {entries.length === 0 ? (
              <p className="text-faint font-body text-[15px] font-semibold py-2">No actions yet</p>
            ) : (
              entries.map((log, idx) => {
                const prevLog = idx > 0 ? entries[idx - 1] : null;
                const turnChange = isTurnChange(log.actionType);
                const showRoundHeader = !prevLog || prevLog.roundNumber !== log.roundNumber;
                const turnName = turnChange ? log.targetName : log.turnParticipantName;
                const prevTurnName = prevLog
                  ? (isTurnChange(prevLog.actionType) ? prevLog.targetName : prevLog.turnParticipantName)
                  : null;
                const showTurnHeader = turnName && (!prevLog || prevTurnName !== turnName || showRoundHeader);

                return (
                  <div key={log.id}>
                    {showRoundHeader && (
                      <div className="font-heading text-[12px] font-semibold tracking-[0.1em] uppercase text-ink border-b border-rule pb-1 pt-2 mb-1">
                        Round {log.roundNumber}
                      </div>
                    )}
                    {showTurnHeader && (
                      <div className="font-body text-[15px] font-semibold text-faint py-0.5 pl-2 border-l-2 border-rule my-1">
                        Turn: {turnName}
                      </div>
                    )}
                    {!turnChange && (
                      <div className={`bg-page-alt border-l-2 ${logBorderColour(log.actionType)} px-2 py-0.5`}>
                        <span className={`font-body text-[15px] font-semibold ${logTextColour(log.actionType)}`}>{log.description}</span>
                        {entryActions?.(log)}
                      </div>
                    )}
                  </div>
                );
              })
            )}
          </div>
          {!isAtBottom && newCount > 0 && (
            <button
              onClick={scrollToBottom}
              className="absolute bottom-2 left-1/2 -translate-x-1/2 flex items-center gap-1 px-3 py-1.5 bg-ink hover:bg-ink/90 text-card font-heading text-[11px] font-semibold shadow-lg"
            >
              <ChevronLeft className="w-3 h-3 rotate-[-90deg]" />
              Scroll to bottom ({newCount} new message{newCount !== 1 ? 's' : ''})
            </button>
          )}
        </div>
      )}
    </div>
  );
}
