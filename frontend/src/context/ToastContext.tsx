import { useCallback, useEffect, useMemo, useRef, useState, type ReactNode } from 'react';
import { createPortal } from 'react-dom';
import { CircleCheck, Info, X, CircleX } from 'lucide-react';
import { apiErrorMessage } from '../api/errors';
import IconButton from '../components/ui/IconButton';
import { MAX_TOASTS, TOAST_DURATION_MS, ToastContext, type ToastApi, type ToastMessage, type ToastTone } from './toast';

const TONE_STYLE: Record<ToastTone, string> = {
  error: 'border-debuff bg-debuff-bg text-debuff',
  success: 'border-buff bg-buff-bg text-buff',
  info: 'border-rule bg-card text-ink',
};

const TONE_ICON = { error: CircleX, success: CircleCheck, info: Info } as const;

/**
 * The app's toasts: short notices stacked along the bottom of the screen, across it on a phone and in the right
 * corner on a wider screen, above any dialog. Each goes after a few seconds or when closed. An error is announced
 * at once (role="alert"), anything else politely (role="status"). The stack is drawn at the end of the body, outside
 * the page's own layout. Showing a message already on screen restarts its
 * time instead of stacking a copy.
 */
export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastMessage[]>([]);
  const nextId = useRef(1);
  const timers = useRef(new Map<number, ReturnType<typeof setTimeout>>());
  // The toasts on screen, read when a new one arrives; the timers and the state change follow from it
  const shown = useRef<ToastMessage[]>([]);

  const dismiss = useCallback((id: number) => {
    const timer = timers.current.get(id);
    if (timer) clearTimeout(timer);
    timers.current.delete(id);
    shown.current = shown.current.filter(t => t.id !== id);
    setToasts(shown.current);
  }, []);

  const startTimer = useCallback((id: number, tone: ToastTone) => {
    const existing = timers.current.get(id);
    if (existing) clearTimeout(existing);
    timers.current.set(id, setTimeout(() => dismiss(id), TOAST_DURATION_MS[tone]));
  }, [dismiss]);

  const show = useCallback((message: string, tone: ToastTone = 'info') => {
    const current = shown.current;
    const same = current.find(t => t.tone === tone && t.message === message);
    if (same) {
      startTimer(same.id, tone);
      return;
    }
    const toast = { id: nextId.current++, tone, message };
    const kept = current.slice(-(MAX_TOASTS - 1));
    current.slice(0, current.length - kept.length).forEach(t => {
      clearTimeout(timers.current.get(t.id));
      timers.current.delete(t.id);
    });
    shown.current = [...kept, toast];
    startTimer(toast.id, tone);
    setToasts(shown.current);
  }, [startTimer]);

  useEffect(() => {
    const pending = timers.current;
    return () => {
      pending.forEach(timer => clearTimeout(timer));
      pending.clear();
    };
  }, []);

  const api = useMemo<ToastApi>(() => ({
    show,
    error: message => show(message, 'error'),
    success: message => show(message, 'success'),
    info: message => show(message, 'info'),
    errorFrom: error => show(apiErrorMessage(error), 'error'),
    dismiss,
  }), [show, dismiss]);

  return (
    <ToastContext.Provider value={api}>
      {children}
      {createPortal(<section
        aria-label="Notifications"
        className="fixed z-[60] bottom-4 inset-x-4 sm:inset-x-auto sm:right-4 sm:w-96 flex flex-col gap-2 pointer-events-none"
      >
        {toasts.map(toast => {
          const Icon = TONE_ICON[toast.tone];
          return (
            <div
              key={toast.id}
              role={toast.tone === 'error' ? 'alert' : 'status'}
              className={`pointer-events-auto flex items-start gap-2 border pl-3 shadow-lg ${TONE_STYLE[toast.tone]}`}
            >
              <Icon aria-hidden="true" className="w-4 h-4 mt-3.5 shrink-0" />
              <p className="flex-1 min-w-0 py-3 font-body text-[15px] font-semibold break-words">{toast.message}</p>
              <IconButton
                label="Dismiss notification"
                onClick={() => dismiss(toast.id)}
                className="size-11 shrink-0 flex items-center justify-center opacity-70 hover:opacity-100"
              >
                <X className="w-4 h-4" />
              </IconButton>
            </div>
          );
        })}
      </section>, document.body)}
    </ToastContext.Provider>
  );
}
