import { useId, useRef, type ReactNode } from 'react';
import { X } from 'lucide-react';
import { useFocusTrap } from '../../hooks/useFocusTrap';
import IconButton from './IconButton';

const WIDTHS = { sm: 'max-w-sm', md: 'max-w-md', lg: 'max-w-lg', xl: 'max-w-2xl' } as const;

interface ModalProps {
  /** The heading, which also names the dialog for screen readers */
  title: ReactNode;
  /** A line under the title, read out after it */
  subtitle?: ReactNode;
  onClose: () => void;
  /** False while the dialog has to stay open, such as during a save: Escape, the backdrop and the close button then do nothing */
  dismissible?: boolean;
  /** The widest the dialog grows */
  size?: keyof typeof WIDTHS;
  /** Buttons along the bottom, below the part that scrolls */
  footer?: ReactNode;
  /** The body has no padding of its own, for content laid out with its own */
  flush?: boolean;
  children: ReactNode;
}

/**
 * A dialog over the page (D100, D171). Render it only while it is open. Focus moves into it,
 * to an autoFocus field or else the close button, and Tab stays inside. Escape or a click on
 * the backdrop closes it, and focus then returns to the element that opened it.
 */
export default function Modal({ title, subtitle, onClose, dismissible = true, size = 'md', footer, flush = false, children }: ModalProps) {
  const panelRef = useRef<HTMLDivElement>(null);
  const titleId = useId();
  const subtitleId = useId();
  useFocusTrap(panelRef, dismissible ? onClose : undefined);

  return (
    <div
      className="fixed inset-0 bg-scrim z-50 flex items-center justify-center p-4"
      // Pressing on the backdrop leaves focus inside the dialog
      onMouseDown={e => { if (e.target === e.currentTarget) e.preventDefault(); }}
      onClick={() => { if (dismissible) onClose(); }}
    >
      <div
        ref={panelRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby={titleId}
        aria-describedby={subtitle ? subtitleId : undefined}
        tabIndex={-1}
        className={`bg-card border border-rule w-full ${WIDTHS[size]} max-h-[90dvh] flex flex-col shadow-lg focus:outline-none`}
        onClick={e => e.stopPropagation()}
      >
        <div className="px-5 py-4 border-b border-rule flex items-center justify-between gap-3 shrink-0">
          <div className="min-w-0">
            <h2 id={titleId} className="font-heading text-[17px] font-bold text-ink flex items-center gap-2">{title}</h2>
            {subtitle && <p id={subtitleId} className="font-body text-[13px] font-semibold text-muted mt-0.5">{subtitle}</p>}
          </div>
          <IconButton label="Close" onClick={onClose} disabled={!dismissible} className="shrink-0 text-muted hover:text-ink disabled:opacity-50">
            <X className="w-5 h-5" />
          </IconButton>
        </div>
        <div className={`${flush ? '' : 'px-5 py-4 '}overflow-y-auto flex-1 min-h-0`}>{children}</div>
        {footer && <div className="px-5 py-4 border-t border-rule flex justify-end gap-3 shrink-0">{footer}</div>}
      </div>
    </div>
  );
}
