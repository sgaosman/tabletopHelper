import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Tracks one request at a time for a button or a group of buttons, so they can be disabled while it runs and a
 * second click sends nothing. `run` starts the request unless one is already running, and resolves with its result,
 * or undefined when it was skipped. A failure still rejects: the caller shows it.
 */
export function usePending() {
  const [pending, setPending] = useState(false);
  const running = useRef(false);
  const mounted = useRef(true);
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const run = useCallback(async <T>(request: () => Promise<T>): Promise<T | undefined> => {
    if (running.current) return undefined;
    running.current = true;
    setPending(true);
    try {
      return await request();
    } finally {
      running.current = false;
      if (mounted.current) setPending(false);
    }
  }, []);

  return { pending, run };
}
