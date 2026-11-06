import { Wifi, WifiOff } from 'lucide-react';

/** Whether the page's WebSocket is connected and receiving the encounter live. */
export default function ConnectionStatus({ isConnected }: { isConnected: boolean }) {
  return (
    <div className="flex items-center gap-1.5">
      {isConnected ? <Wifi className="w-4 h-4 text-buff" /> : <WifiOff className="w-4 h-4 text-debuff" />}
      <span className="font-body text-[15px] font-semibold text-muted">{isConnected ? 'Live' : 'Disconnected'}</span>
    </div>
  );
}
