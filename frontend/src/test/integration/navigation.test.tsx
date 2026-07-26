import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import App from '../../App';

describe('Navigation & Routing', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('navigates from role selection to DM dashboard', async () => {
    window.history.pushState({}, '', '/select-role');
    render(<App />);
    await waitFor(() => screen.getByText('Dungeon Master'));
    const user = userEvent.setup();
    await user.click(screen.getByText('Dungeon Master'));
    await waitFor(() => {
      expect(window.location.pathname).toBe('/dm');
    });
  });

  it('navigates from role selection to player dashboard', async () => {
    window.history.pushState({}, '', '/select-role');
    render(<App />);
    await waitFor(() => screen.getByText('Player'));
    const user = userEvent.setup();
    await user.click(screen.getByText('Player'));
    await waitFor(() => {
      expect(window.location.pathname).toBe('/player');
    });
  });

  it('shows player dashboard with character list', async () => {
    window.history.pushState({}, '', '/player');
    render(<App />);
    await waitFor(() => {
      expect(screen.getByText('My Characters')).toBeInTheDocument();
    });
  });

  it('renders DM dashboard after navigation', async () => {
    window.history.pushState({}, '', '/dm');
    render(<App />);
    await waitFor(() => {
      const dashboards = screen.getAllByText('Dashboard');
    expect(dashboards.length).toBeGreaterThanOrEqual(1);
    });
  });

  it('redirects unknown routes to login when unauthenticated', () => {
    localStorage.clear();
    window.history.pushState({}, '', '/some/nonexistent/path');
    render(<App />);
    // App redirects * to /login
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
  });
});
