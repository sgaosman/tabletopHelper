import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { render, clearAuthToken } from '../test/test-utils';
import RegisterPage from './RegisterPage';

describe('RegisterPage', () => {
  beforeEach(() => {
    clearAuthToken();
  });

  it('renders registration form with all fields', () => {
    render(<RegisterPage />);
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
    expect(screen.getByLabelText(/Display Name/)).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /create account/i })).toBeInTheDocument();
  });

  it('has a link to sign in page', () => {
    render(<RegisterPage />);
    expect(screen.getByRole('link', { name: /sign in/i })).toHaveAttribute('href', '/login');
  });

  it('validates username minimum length', () => {
    render(<RegisterPage />);
    expect(screen.getByLabelText('Username')).toHaveAttribute('minLength', '3');
  });

  it('validates email type', () => {
    render(<RegisterPage />);
    expect(screen.getByLabelText('Email')).toHaveAttribute('type', 'email');
  });

  it('validates password minimum length', () => {
    render(<RegisterPage />);
    expect(screen.getByLabelText('Password')).toHaveAttribute('minLength', '8');
  });

  it('has submit button with correct attributes', () => {
    render(<RegisterPage />);
    expect(screen.getByRole('button', { name: /create account/i })).toHaveAttribute('type', 'submit');
  });

  it('displays error when registration fails', async () => {
    const { server } = await import('../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.post('/api/auth/register', () => HttpResponse.json({ error: 'Username taken' }, { status: 409 }))
    );
    render(<RegisterPage />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'takenuser');
    await user.type(screen.getByLabelText('Email'), 'taken@test.com');
    await user.type(screen.getByLabelText('Password'), 'password123');
    await user.click(screen.getByRole('button', { name: /create account/i }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Username taken');
    });
  });

  it('redirects to role selection after successful registration', async () => {
    render(<RegisterPage />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'newplayer');
    await user.type(screen.getByLabelText('Email'), 'new@test.com');
    await user.type(screen.getByLabelText('Password'), 'password123');
    await user.click(screen.getByRole('button', { name: /create account/i }));
    await waitFor(() => {
      expect(window.location.pathname).toBe('/select-role');
    });
  });

  it('submits without display name (optional field)', async () => {
    render(<RegisterPage />);
    const user = userEvent.setup();
    await user.type(screen.getByLabelText('Username'), 'newplayer');
    await user.type(screen.getByLabelText('Email'), 'new@test.com');
    await user.type(screen.getByLabelText('Password'), 'password123');
    await user.click(screen.getByRole('button', { name: /create account/i }));
    await waitFor(() => {
      expect(window.location.pathname).toBe('/select-role');
    });
  });
});
