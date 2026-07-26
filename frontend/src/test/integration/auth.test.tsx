import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render, clearAuthToken, setupAuthToken } from '../../test/test-utils';
import { makeMockCharacter } from '../../test/mocks/handlers';
import App from '../../App';

describe('Auth Integration', () => {
  beforeEach(() => {
    clearAuthToken();
  });

  it('renders login page by default (route / redirects to /login)', () => {
    window.history.pushState({}, '', '/');
    render(<App />);
    expect(screen.getByText('Tabletop Helper')).toBeInTheDocument();
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
  });

  it('allows user to login and see role selection', async () => {
    window.history.pushState({}, '', '/login');
    render(<App />);
    const user = userEvent.setup();

    await user.type(screen.getByLabelText('Username'), 'testuser');
    await user.type(screen.getByLabelText('Password'), 'password123');
    await user.click(screen.getByRole('button', { name: /sign in/i }));

    await waitFor(() => {
      expect(screen.getByText(/Welcome, Test User/)).toBeInTheDocument();
      expect(screen.getByText('Dungeon Master')).toBeInTheDocument();
      expect(screen.getByText('Player')).toBeInTheDocument();
    });
  });

  it('shows role selection page with both role options', async () => {
    setupAuthToken();
    window.history.pushState({}, '', '/select-role');
    render(<App />);
    await waitFor(() => {
      expect(screen.getByText('Dungeon Master')).toBeInTheDocument();
      expect(screen.getByText('Player')).toBeInTheDocument();
    });
  });

  it('shows sign out button on role selection', async () => {
    setupAuthToken();
    window.history.pushState({}, '', '/select-role');
    render(<App />);
    await waitFor(() => {
      expect(screen.getByText('Sign out')).toBeInTheDocument();
    });
  });

  it('redirects to login when accessing protected route without auth', () => {
    window.history.pushState({}, '', '/dm');
    render(<App />);
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
  });

  it('redirects authenticated user away from login page', async () => {
    setupAuthToken();
    window.history.pushState({}, '', '/login');
    render(<App />);
    await waitFor(() => {
      expect(screen.getByText(/Welcome, Test User/)).toBeInTheDocument();
    });
  });
});
