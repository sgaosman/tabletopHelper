import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import ConnectionStatus, { ConnectionBanner } from './ConnectionStatus';

describe('ConnectionStatus', () => {
  it('names each state of the connection', () => {
    const { rerender } = render(<ConnectionStatus state="connected" />);
    expect(screen.getByText('Live')).toHaveClass('text-[15px]');

    rerender(<ConnectionStatus state="connecting" />);
    expect(screen.getByText('Connecting')).toBeInTheDocument();

    rerender(<ConnectionStatus state="reconnecting" />);
    expect(screen.getByText('Reconnecting')).toBeInTheDocument();

    rerender(<ConnectionStatus state="offline" />);
    expect(screen.getByText('Offline')).toBeInTheDocument();
  });
});

describe('ConnectionBanner', () => {
  it('shows nothing while connected or connecting for the first time', () => {
    const { rerender } = render(<ConnectionBanner state="connected" onRetry={() => {}} />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();

    rerender(<ConnectionBanner state="connecting" onRetry={() => {}} />);
    expect(screen.queryByRole('status')).not.toBeInTheDocument();
  });

  it('says the connection is being retried, and retries at once on request', async () => {
    const onRetry = vi.fn();
    render(<ConnectionBanner state="reconnecting" onRetry={onRetry} />);

    expect(screen.getByRole('status')).toHaveTextContent(/Connection lost. Reconnecting/);
    await userEvent.click(screen.getByRole('button', { name: /Retry now/ }));
    expect(onRetry).toHaveBeenCalledOnce();
  });

  it('says the browser is offline', () => {
    render(<ConnectionBanner state="offline" onRetry={() => {}} />);
    expect(screen.getByRole('status')).toHaveTextContent(/You are offline/);
  });
});
