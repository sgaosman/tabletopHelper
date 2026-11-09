import { RefreshCw, Wifi, WifiOff } from 'lucide-react';
import type { ConnectionState } from '../../hooks/useWebSocket';
import Spinner from '../ui/Spinner';

const LABELS: Record<ConnectionState, string> = {
  connecting: 'Connecting',
  connected: 'Live',
  reconnecting: 'Reconnecting',
  offline: 'Offline',
};

/** Whether the page's WebSocket is connected and receiving the encounter live, for the page's header. */
export default function ConnectionStatus({ state }: { state: ConnectionState }) {
  return (
    <div className="flex items-center gap-1.5">
      {state === 'connected' && <Wifi aria-hidden="true" className="w-4 h-4 text-buff" />}
      {(state === 'connecting' || state === 'reconnecting') && <Spinner className="text-muted" />}
      {state === 'offline' && <WifiOff aria-hidden="true" className="w-4 h-4 text-debuff" />}
      <span className="font-body text-[15px] font-semibold text-muted">{LABELS[state]}</span>
    </div>
  );
}

/**
 * Says, under the header, that the live connection has dropped: while it is trying again, or while the browser is
 * offline. What happened meanwhile loads when it is back, so the banner says the page may be behind. "Retry now"
 * skips the wait before the next attempt. Nothing shows while connected or connecting for the first time.
 */
export function ConnectionBanner({ state, onRetry }: { state: ConnectionState; onRetry: () => void }) {
  if (state !== 'reconnecting' && state !== 'offline') return null;
  const offline = state === 'offline';
  return (
    <div
      role="status"
      className={`flex flex-wrap items-center gap-x-3 gap-y-1 px-4 sm:px-6 py-2 border-b ${
        offline ? 'bg-debuff-bg border-debuff text-debuff' : 'bg-page-alt border-rule text-muted'
      }`}
    >
      {offline ? <WifiOff aria-hidden="true" className="w-4 h-4 shrink-0" /> : <Spinner />}
      <p className="flex-1 min-w-0 font-body text-[15px] font-semibold">
        {offline
          ? 'You are offline. The encounter will catch up when the connection is back.'
          : 'Connection lost. Reconnecting; the encounter may be behind until then.'}
      </p>
      <button
        type="button"
        onClick={onRetry}
        className="inline-flex items-center gap-1.5 min-h-11 px-3 font-body text-[15px] font-semibold underline underline-offset-2 hover:no-underline"
      >
        <RefreshCw aria-hidden="true" className="w-4 h-4" /> Retry now
      </button>
    </div>
  );
}
