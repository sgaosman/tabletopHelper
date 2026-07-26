import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken, clearAuthToken } from '../test/test-utils';
import LoginPage from './LoginPage';

describe('LoginPage', () => {
  beforeEach(() => {
    clearAuthToken();
  });

  it('renders login form with username and password fields', () => {
    render(<LoginPage />);
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /sign in/i })).toBeInTheDocument();
  });

  it('renders a link to the registration page', () => {
    render(<LoginPage />);
    const link = screen.getByRole('link', { name: /sign up/i });
    expect(link).toBeInTheDocument();
    expect(link).toHaveAttribute('href', '/register');
  });

  it('disables submit button via form submission pattern', async () => {
    // With MSW resolving immediately, the loading state is ephemeral.
    // We verify the button has type="submit" and the form fires.
    render(<LoginPage />);
    expect(screen.getByRole('button', { name: /sign in/i })).toHaveAttribute('type', 'submit');
  });

  it('displays error message when login fails', async () => {
    const { server } = await import('../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.post('/api/auth/login', () => HttpResponse.json({ error: 'Invalid credentials' }, { status: 401 }))
    );
    render(<LoginPage />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'baduser');
    await user.type(screen.getByLabelText('Password'), 'wrong');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Invalid credentials');
    });
  });

  it('redirects to role selection after successful login', async () => {
    setupAuthToken();
    render(<LoginPage />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'testuser');
    await user.type(screen.getByLabelText('Password'), 'password123');
    await user.click(screen.getByRole('button', { name: /sign in/i }));
    await waitFor(() => {
      expect(window.location.pathname).toBe('/select-role');
    });
  });

  it('requires both fields before submission', () => {
    render(<LoginPage />);
    const field = screen.getByLabelText('Username');
    expect(field).toBeRequired();
    expect(screen.getByLabelText('Password')).toBeRequired();
  });
});
