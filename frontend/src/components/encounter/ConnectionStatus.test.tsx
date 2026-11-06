import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import ConnectionStatus from './ConnectionStatus';

describe('ConnectionStatus', () => {
  it('says Live while connected and Disconnected otherwise', () => {
    const { rerender } = render(<ConnectionStatus isConnected />);
    expect(screen.getByText('Live')).toHaveClass('text-[15px]');

    rerender(<ConnectionStatus isConnected={false} />);
    expect(screen.getByText('Disconnected')).toBeInTheDocument();
  });
});
