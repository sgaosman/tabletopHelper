import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { server } from '../test/mocks/server';
import { setupAuthToken } from '../test/test-utils';
import { getAccessToken } from '../api/tokenStore';
import { AuthProvider, useAuth } from './AuthContext';

function AuthProbe() {
  const { status, user, isAuthenticated, login, register, logout } = useAuth();
  return (
    <div>
      <p>Status: {status}</p>
      <p>{isAuthenticated ? `Signed in as ${user?.displayName}` : 'Signed out'}</p>
      <button onClick={() => void login({ username: 'testuser', password: 'password123' })}>Log in</button>
      <button onClick={() => void register({ username: 'newuser', email: 'new@test.com', password: 'password123' })}>
        Register
      </button>
      <button onClick={() => void logout()}>Log out</button>
    </div>
  );
}

function renderProvider() {
  return render(
    <AuthProvider>
      <AuthProbe />
    </AuthProvider>,
  );
}

/** Counts POST /api/auth/logout calls, answering with `respond`. */
function logoutEndpoint(respond: () => Response = () => new HttpResponse(null, { status: 204 })) {
  let calls = 0;
  server.use(
    http.post('/api/auth/logout', () => {
      calls += 1;
      return respond();
    }),
  );
  return () => calls;
}

describe('AuthContext', () => {
  it('starts loading, then signs in from the refresh cookie and keeps the token in memory', async () => {
    setupAuthToken({ userId: 'user-001', username: 'testuser', displayName: 'Test User', accessToken: 'cookie-token' });

    renderProvider();

    expect(screen.getByText('Status: loading')).toBeInTheDocument();
    expect(await screen.findByText('Status: authenticated')).toBeInTheDocument();
    expect(screen.getByText('Signed in as Test User')).toBeInTheDocument();
    expect(getAccessToken()).toBe('cookie-token');
    expect(localStorage.getItem('accessToken')).toBeNull();
  });

  it('becomes anonymous when the browser has no refresh cookie', async () => {
    renderProvider();

    expect(await screen.findByText('Status: anonymous')).toBeInTheDocument();
    expect(screen.getByText('Signed out')).toBeInTheDocument();
    expect(getAccessToken()).toBeNull();
  });

  it('deletes the tokens browsers kept in localStorage before D156', async () => {
    localStorage.setItem('accessToken', 'old-access-token');
    localStorage.setItem('refreshToken', 'old-refresh-token');
    localStorage.setItem('user', JSON.stringify({ userId: 'user-001', username: 'testuser', displayName: 'Test User' }));
    localStorage.setItem('theme', 'tavern');

    renderProvider();

    await screen.findByText('Status: anonymous');
    expect(localStorage.getItem('accessToken')).toBeNull();
    expect(localStorage.getItem('refreshToken')).toBeNull();
    expect(localStorage.getItem('user')).toBeNull();
    expect(localStorage.getItem('theme')).toBe('tavern');
  });

  it('signs in on login, keeping the access token in memory only', async () => {
    renderProvider();
    await screen.findByText('Status: anonymous');

    await userEvent.click(screen.getByRole('button', { name: 'Log in' }));

    expect(await screen.findByText('Signed in as Test User')).toBeInTheDocument();
    expect(getAccessToken()).toBe('mock-access-token');
    expect(localStorage.length).toBe(0);
  });

  it('signs in on register', async () => {
    renderProvider();
    await screen.findByText('Status: anonymous');

    await userEvent.click(screen.getByRole('button', { name: 'Register' }));

    expect(await screen.findByText('Signed in as New User')).toBeInTheDocument();
    expect(getAccessToken()).toBe('mock-access-token-new');
  });

  it('asks the server to end the session on logout, then forgets the token', async () => {
    setupAuthToken();
    const logoutCalls = logoutEndpoint();
    renderProvider();
    await screen.findByText('Status: authenticated');

    await userEvent.click(screen.getByRole('button', { name: 'Log out' }));

    expect(await screen.findByText('Status: anonymous')).toBeInTheDocument();
    expect(logoutCalls()).toBe(1);
    expect(getAccessToken()).toBeNull();
  });

  it('still signs out of this tab when the logout request fails', async () => {
    setupAuthToken();
    const logoutCalls = logoutEndpoint(() => HttpResponse.error());
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => {});
    renderProvider();
    await screen.findByText('Status: authenticated');

    await userEvent.click(screen.getByRole('button', { name: 'Log out' }));

    expect(await screen.findByText('Status: anonymous')).toBeInTheDocument();
    expect(logoutCalls()).toBe(1);
    expect(getAccessToken()).toBeNull();
    expect(warn).toHaveBeenCalledWith('Sign-out request failed', expect.anything());
    warn.mockRestore();
  });
});
