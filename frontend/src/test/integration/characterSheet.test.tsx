import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import App from '../../App';

describe('Character Sheet Integration', () => {
  beforeEach(() => {
    setupAuthToken();
    window.history.pushState({}, '', '/player/characters/char-001');
  });

  it('renders the character sheet page with header tabs', async () => {
    render(<App />);
    await waitFor(() => {
      expect(screen.getByRole('tablist')).toBeInTheDocument();
    });
  }, 10000);

  it('has all six character sheet tabs', async () => {
    render(<App />);
    await waitFor(() => {
      expect(screen.getByRole('tab', { name: 'Stats' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Actions' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Spells' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Inventory' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Features' })).toBeInTheDocument();
      expect(screen.getByRole('tab', { name: 'Journal' })).toBeInTheDocument();
    });
  }, 10000);

  it('shows character name in the header', async () => {
    render(<App />);
    await waitFor(() => {
      expect(screen.getByText('Test Character')).toBeInTheDocument();
    });
  }, 10000);
});
