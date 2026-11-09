import { useContext } from 'react';
import { ToastContext, type ToastApi } from '../context/toast';

// Outside a ToastProvider, such as a component test that renders one component alone, notices go nowhere
const NO_TOASTS: ToastApi = {
  show: () => {},
  error: () => {},
  success: () => {},
  info: () => {},
  errorFrom: () => {},
  dismiss: () => {},
};

/** The app's toasts (context/ToastContext.tsx). */
export function useToast(): ToastApi {
  return useContext(ToastContext) ?? NO_TOASTS;
}
