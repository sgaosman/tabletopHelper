import { useEffect, useRef, type RefObject } from 'react';

/**
 * Closes a popup on Escape or on a mouse press outside containerRef.
 * Escape also returns focus to returnFocusRef, usually the button that opened the popup.
 */
export function useDismiss(
  open: boolean,
  containerRef: RefObject<HTMLElement | null>,
  onClose: () => void,
  returnFocusRef?: RefObject<HTMLElement | null>,
) {
  const close = useRef(onClose);

  // Always call the latest onClose without re-subscribing on every render
  useEffect(() => {
    close.current = onClose;
  });

  useEffect(() => {
    if (!open) return;
    function onMouseDown(e: MouseEvent) {
      if (!containerRef.current?.contains(e.target as Node)) close.current();
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key !== 'Escape') return;
      close.current();
      returnFocusRef?.current?.focus();
    }
    document.addEventListener('mousedown', onMouseDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('mousedown', onMouseDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open, containerRef, returnFocusRef]);
}
