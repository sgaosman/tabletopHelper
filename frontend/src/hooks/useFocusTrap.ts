import { useEffect, useRef, useState, type RefObject } from 'react';

const FOCUSABLE = 'a[href], button, input, select, textarea, [tabindex], [contenteditable="true"]';

/** The open traps, most recent last. Only that one answers keys and focus changes. */
const openTraps: HTMLElement[] = [];

/** The elements inside container that Tab can reach, in page order. */
function tabbableElements(container: HTMLElement): HTMLElement[] {
  return Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE)).filter(el =>
    el.tabIndex >= 0
    && !el.matches(':disabled, input[type="hidden"]')
    && !el.closest('[hidden], [inert]')
    // Leaves out elements a stylesheet hides. jsdom has no layout, and no checkVisibility either
    && (el.checkVisibility?.() ?? true));
}

/** Focuses the first of candidates that takes focus, or else the container itself. */
function focusFirstOf(candidates: HTMLElement[], container: HTMLElement) {
  for (const el of candidates) {
    el.focus();
    if (document.activeElement === el) return;
  }
  container.focus();
}

/**
 * Keeps keyboard focus inside a dialog while it is mounted (D100):
 * - focus moves to the first focusable element, unless an `autoFocus` inside already took it
 * - Tab and Shift+Tab wrap around inside, and focus that strays outside is brought back
 * - Escape calls `onEscape`, when given
 * - focus returns to the element that had it before, usually the button that opened the dialog
 *
 * When dialogs stack, only the most recent one responds. The container needs `tabIndex={-1}`
 * so it can hold focus when nothing inside it can.
 */
export function useFocusTrap(containerRef: RefObject<HTMLElement | null>, onEscape?: () => void) {
  // Read while rendering, before an autoFocus inside the dialog moves focus
  const [focusedBeforeOpening] = useState(() => document.activeElement);
  const escape = useRef(onEscape);

  // Always call the latest onEscape without re-subscribing on every render
  useEffect(() => {
    escape.current = onEscape;
  });

  useEffect(() => {
    const container = containerRef.current;
    if (!container) return;
    const opener = container.contains(document.activeElement) ? focusedBeforeOpening : document.activeElement;
    openTraps.push(container);
    if (!container.contains(document.activeElement)) focusFirstOf(tabbableElements(container), container);

    let backwards = false;
    const isTopmost = () => openTraps[openTraps.length - 1] === container;

    const onKeyDown = (e: KeyboardEvent) => {
      if (e.defaultPrevented || !isTopmost()) return;
      if (e.key === 'Escape' && escape.current) {
        e.preventDefault();
        escape.current();
        return;
      }
      if (e.key !== 'Tab') return;
      backwards = e.shiftKey;
      const tabbable = tabbableElements(container);
      const first = tabbable[0];
      const last = tabbable[tabbable.length - 1];
      const active = document.activeElement;
      // Focus can sit on the page body, for example after the focused element was removed
      const outside = !container.contains(active);
      if (!first) {
        e.preventDefault();
        container.focus();
      } else if (backwards && (outside || active === first || active === container)) {
        e.preventDefault();
        focusFirstOf(tabbable.reverse(), container);
      } else if (!backwards && (outside || active === last)) {
        e.preventDefault();
        focusFirstOf(tabbable, container);
      }
    };

    // Catches focus that leaves anyway, such as a Tab past an element a stylesheet hides
    const onFocusIn = (e: FocusEvent) => {
      if (!isTopmost() || container.contains(e.target as Node)) return;
      const tabbable = tabbableElements(container);
      focusFirstOf(backwards ? tabbable.reverse() : tabbable, container);
    };

    document.addEventListener('keydown', onKeyDown);
    document.addEventListener('focusin', onFocusIn);
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.removeEventListener('focusin', onFocusIn);
      openTraps.splice(openTraps.lastIndexOf(container), 1);
      // Strict Mode re-runs effects on a dialog that is still on the page; focus stays inside it then
      if (container.isConnected) return;
      const active = document.activeElement;
      const focusWasInside = !active || active === document.body || container.contains(active);
      if (focusWasInside && opener instanceof HTMLElement && opener !== document.body && opener.isConnected) {
        opener.focus();
      }
    };
  }, [containerRef, focusedBeforeOpening]);
}
