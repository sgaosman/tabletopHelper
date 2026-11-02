import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { render, screen, setupAuthToken } from '../../test/test-utils';
import { server } from '../../test/mocks/server';
import { getAccessToken } from '../../api/tokenStore';
import AccountMenu from './AccountMenu';

describe('AccountMenu', () => {
  beforeEach(() => {
    setupAuthToken();
    window.history.pushState({}, '', '/player');
  });

  afterEach(() => {
    delete document.documentElement.dataset.theme;
    localStorage.removeItem('theme');
    window.history.pushState({}, '', '/');
  });

  it('lists the four themes, with Tourmaline chosen by default', async () => {
    render(<AccountMenu />);

    await userEvent.click(screen.getByRole('button', { name: 'Account' }));

    for (const name of ['Parchment & Ink', 'Tavern by Candlelight', "Adventurer's Handbook"]) {
      expect(screen.getByRole('radio', { name })).not.toBeChecked();
    }
    expect(screen.getByRole('radio', { name: 'Tourmaline' })).toBeChecked();
  });

  it('switches the page to the theme you pick and remembers it', async () => {
    render(<AccountMenu />);
    await userEvent.click(screen.getByRole('button', { name: 'Account' }));

    await userEvent.click(screen.getByRole('radio', { name: 'Tavern by Candlelight' }));

    expect(document.documentElement.dataset.theme).toBe('tavern');
    expect(localStorage.getItem('theme')).toBe('tavern');
    expect(screen.getByRole('radio', { name: 'Tavern by Candlelight' })).toBeChecked();
  });

  it('closes on Escape and puts focus back on the Account button', async () => {
    render(<AccountMenu />);
    const accountButton = screen.getByRole('button', { name: 'Account' });
    await userEvent.click(accountButton);

    await userEvent.keyboard('{Escape}');

    expect(screen.queryByRole('radio', { name: 'Tourmaline' })).not.toBeInTheDocument();
    expect(accountButton).toHaveFocus();
    expect(accountButton).toHaveAttribute('aria-expanded', 'false');
  });

  it('closes when you click elsewhere on the page', async () => {
    render(<><AccountMenu /><p>Elsewhere</p></>);
    await userEvent.click(screen.getByRole('button', { name: 'Account' }));

    await userEvent.click(screen.getByText('Elsewhere'));

    expect(screen.queryByRole('radio', { name: 'Tourmaline' })).not.toBeInTheDocument();
  });

  it('holds Switch Role, and Sign Out, which ends the session on the server', async () => {
    let logoutCalls = 0;
    server.use(
      http.post('/api/auth/logout', () => {
        logoutCalls += 1;
        return new HttpResponse(null, { status: 204 });
      }),
    );
    render(<AccountMenu />);
    await userEvent.click(screen.getByRole('button', { name: 'Account' }));
    await userEvent.click(screen.getByRole('button', { name: 'Switch Role' }));
    expect(window.location.pathname).toBe('/select-role');

    await userEvent.click(screen.getByRole('button', { name: 'Account' }));
    await userEvent.click(screen.getByRole('button', { name: 'Sign Out' }));

    await vi.waitFor(() => expect(getAccessToken()).toBeNull());
    expect(logoutCalls).toBe(1);
  });
});
