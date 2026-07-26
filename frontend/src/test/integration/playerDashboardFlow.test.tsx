import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import App from '../../App';

describe('Player Dashboard Flow', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('loads dashboard with character cards', async () => {
    window.history.pushState({}, '', '/player');
    render(<App />);
    await waitFor(() => {
      expect(screen.getByText('Gimli')).toBeInTheDocument();
      expect(screen.getByText(/Level 5/)).toBeInTheDocument();
      expect(screen.getByText(/HP 30\/30/)).toBeInTheDocument();
      expect(screen.getByText(/AC 16/)).toBeInTheDocument();
    });
  });

  it('opens delete confirmation and requires exact name', async () => {
    window.history.pushState({}, '', '/player');
    render(<App />);
    await waitFor(() => screen.getByText('Gimli'));
    const user = userEvent.setup();
    await user.click(screen.getByTitle('Delete character'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    const deleteBtn = screen.getByRole('button', { name: 'Delete' });
    expect(deleteBtn).toBeDisabled();
    const nameInput = screen.getByPlaceholderText('Gimli');
    await user.type(nameInput, 'WrongName');
    expect(deleteBtn).toBeDisabled();
    await user.clear(nameInput);
    await user.type(nameInput, 'Gimli');
    expect(deleteBtn).not.toBeDisabled();
  });

  it('clears invite code input on successful join', async () => {
    window.history.pushState({}, '', '/player');
    render(<App />);
    const user = userEvent.setup();
    const input = screen.getByPlaceholderText('Enter invite code');
    await user.type(input, 'ABC123XY');
    await user.click(screen.getByRole('button', { name: 'Join' }));
    await waitFor(() => {
      expect(input).toHaveValue('');
    });
  });
});
