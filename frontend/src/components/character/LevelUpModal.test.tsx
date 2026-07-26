import { describe, it, expect } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import { makeMockCharacter } from '../../test/mocks/handlers';
import LevelUpModal from './LevelUpModal';

describe('LevelUpModal', () => {
  const character = makeMockCharacter('char-001', 'Gimli', 'Fighter', 5);

  beforeEach(() => {
    setupAuthToken();
  });

  it('renders the modal with title', async () => {
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: 'Level Up' })).toBeInTheDocument();
    });
  });

  it('shows loading state initially', () => {
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByText('Loading classes...')).toBeInTheDocument();
  });

  it('has cancel and confirm buttons', async () => {
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => {}} />);
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Cancel' })).toBeInTheDocument();
      expect(screen.getByRole('button', { name: 'Level Up' })).toBeInTheDocument();
    });
  });

  it('has correct ARIA attributes', () => {
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => {}} />);
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('dialog')).toHaveAttribute('aria-labelledby', 'levelup-title');
  });

  it('calls onClose when Cancel is clicked', async () => {
    let closed = false;
    render(<LevelUpModal character={character} onComplete={() => {}} onClose={() => { closed = true; }} />);
    await waitFor(() => screen.getByRole('button', { name: 'Cancel' }));
    const user = userEvent.setup();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(closed).toBe(true);
  });
});
