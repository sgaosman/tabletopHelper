import { describe, it, expect, afterEach } from 'vitest';
import userEvent from '@testing-library/user-event';
import { render, screen, within } from '../../test/test-utils';
import NavBar from './NavBar';

describe('NavBar', () => {
  afterEach(() => {
    window.history.pushState({}, '', '/');
  });

  it('gives players the Spells, Items and Conditions pages', async () => {
    window.history.pushState({}, '', '/player');
    render(<NavBar />);

    for (const label of ['Spells', 'Items', 'Conditions']) {
      expect(screen.getByRole('button', { name: label })).toBeInTheDocument();
    }
    await userEvent.click(screen.getByRole('button', { name: 'Conditions' }));

    // Stays on the player side, so the bar keeps the player links
    expect(window.location.pathname).toBe('/player/conditions');
    expect(screen.queryByRole('button', { name: 'Bestiary' })).not.toBeInTheDocument();
  });

  it('offers the same links from a menu button for narrow screens', async () => {
    window.history.pushState({}, '', '/dm');
    render(<NavBar />);

    await userEvent.click(screen.getByRole('button', { name: 'Menu' }));
    const menu = within(document.getElementById('nav-links-menu')!);
    expect(menu.getByRole('button', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
    await userEvent.click(menu.getByRole('button', { name: 'Bestiary' }));

    expect(window.location.pathname).toBe('/dm/bestiary');
    expect(document.getElementById('nav-links-menu')).not.toBeInTheDocument();
  });

  it('keeps Switch Role and Sign Out inside the Account menu', async () => {
    window.history.pushState({}, '', '/player');
    render(<NavBar />);
    expect(screen.queryByRole('button', { name: 'Sign Out' })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole('button', { name: 'Account' }));

    expect(screen.getByRole('button', { name: 'Switch Role' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign Out' })).toBeInTheDocument();
  });
});
