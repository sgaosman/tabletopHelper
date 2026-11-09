import { createContext } from 'react';

/** How a toast reads: a failure, a change that went through, or something to know. */
export type ToastTone = 'error' | 'success' | 'info';

/** How long a toast stays: an error long enough to read twice, anything else briefly. */
export const TOAST_DURATION_MS: Record<ToastTone, number> = { error: 7000, success: 4000, info: 4000 };

/** The most toasts shown at once; a new one pushes out the oldest. */
export const MAX_TOASTS = 4;

export interface ToastMessage {
  id: number;
  tone: ToastTone;
  message: string;
}

/** What useToast() gives a component: short notices that stack in a corner and go away on their own. */
export interface ToastApi {
  show: (message: string, tone?: ToastTone) => void;
  error: (message: string) => void;
  success: (message: string) => void;
  info: (message: string) => void;
  /** Shows a failed request's message as the server words it (api/errors.ts). Pass it straight to `.catch`. */
  errorFrom: (error: unknown) => void;
  dismiss: (id: number) => void;
}

export const ToastContext = createContext<ToastApi | null>(null);
