import { describe, it, expect, beforeEach } from 'vitest';
import { screen, waitFor } from '../../test/test-utils';
import userEvent from '@testing-library/user-event';
import { render, setupAuthToken } from '../../test/test-utils';
import PlayerDashboard from './PlayerDashboard';

describe('PlayerDashboard', () => {
  beforeEach(() => {
    setupAuthToken();
  });

  it('shows loading state while data is fetched', () => {
    render(<PlayerDashboard />);
    const elements = screen.queryAllByText('Loading...');
    expect(elements.length).toBeGreaterThan(0);
  });

  it('shows empty state when no campaigns or characters exist', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.get('/api/campaigns', () => HttpResponse.json([])),
      http.get('/api/characters', () => HttpResponse.json([])),
    );
    render(<PlayerDashboard />);
    await waitFor(() => {
      expect(screen.getByText(/You haven't joined any campaigns yet/)).toBeInTheDocument();
    });
    expect(screen.getByText('Create New Character')).toBeInTheDocument();
  });

  it('shows campaign and character when data is populated', async () => {
    render(<PlayerDashboard />);
    await waitFor(() => {
      expect(screen.getByText('Test Campaign')).toBeInTheDocument();
    });
    await waitFor(() => {
      expect(screen.getByText('Gimli')).toBeInTheDocument();
      expect(screen.getByText(/Level 5/)).toBeInTheDocument();
      expect(screen.getByText(/HP 30\/30/)).toBeInTheDocument();
      expect(screen.getByText(/AC 16/)).toBeInTheDocument();
    });
  });

  it("names each of a multiclass character's classes with its level", async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockCharacter } = await import('../../test/mocks/handlers');
    server.use(http.get('/api/characters', () => HttpResponse.json([{
      ...makeMockCharacter('char-001', 'Aria', 'Paladin', 6),
      multiclassEntries: [
        { classId: 'cls-paladin', className: 'Paladin', level: 5 },
        { classId: 'cls-sorcerer', className: 'Sorcerer', level: 1 },
      ],
    }])));
    render(<PlayerDashboard />);

    expect(await screen.findByText(/Level 6 Human Paladin 5 \/ Sorcerer 1/)).toBeInTheDocument();
  });

  it('handles join campaign error', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(
      http.post('/api/campaigns/join', () =>
        HttpResponse.json({ error: 'Invalid invite code' }, { status: 400 })
      ),
    );
    render(<PlayerDashboard />);
    const user = userEvent.setup();
    const input = screen.getByPlaceholderText('Enter invite code');
    await user.type(input, 'WRONG');
    await user.click(screen.getByRole('button', { name: 'Join' }));
    await waitFor(() => {
      expect(screen.getByRole('alert')).toHaveTextContent('Invalid invite code');
    });
  });

  it('opens delete confirmation modal', async () => {
    render(<PlayerDashboard />);
    await waitFor(() => screen.getByText('Gimli'));
    const user = userEvent.setup();
    await user.click(screen.getByTitle('Delete character'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText(/Are you sure you want to delete/)).toBeInTheDocument();
  });

  it('disables Delete button until correct name is typed', async () => {
    render(<PlayerDashboard />);
    await waitFor(() => screen.getByText('Gimli'));
    const user = userEvent.setup();
    await user.click(screen.getByTitle('Delete character'));
    const deleteBtn = screen.getByRole('button', { name: 'Delete' });
    expect(deleteBtn).toBeDisabled();
    const nameInput = screen.getByPlaceholderText('Gimli');
    await user.type(nameInput, 'WrongName');
    expect(deleteBtn).toBeDisabled();
    await user.clear(nameInput);
    await user.type(nameInput, 'Gimli');
    expect(deleteBtn).not.toBeDisabled();
  });

  it('deletes character on confirm', async () => {
    render(<PlayerDashboard />);
    await waitFor(() => screen.getByText('Gimli'));
    const user = userEvent.setup();
    await user.click(screen.getByTitle('Delete character'));
    await user.type(screen.getByPlaceholderText('Gimli'), 'Gimli');
    const deleteBtn = screen.getByRole('button', { name: 'Delete' });
    expect(deleteBtn).not.toBeDisabled();
  });

  it('removes the character once it is deleted and closes the modal', async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    const { makeMockCharacter } = await import('../../test/mocks/handlers');
    let characters = [makeMockCharacter('char-001', 'Gimli', 'Fighter', 5)];
    let deleted: string | undefined;
    server.use(
      http.get('/api/characters', () => HttpResponse.json(characters)),
      http.delete('/api/characters/:id', ({ params }) => {
        deleted = params.id as string;
        characters = characters.filter(c => c.id !== params.id);
        return new HttpResponse(null, { status: 204 });
      }),
    );
    render(<PlayerDashboard />);
    const user = userEvent.setup();
    await user.click(await screen.findByTitle('Delete character'));
    await user.type(screen.getByPlaceholderText('Gimli'), 'Gimli');
    await user.click(screen.getByRole('button', { name: 'Delete' }));

    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(screen.queryByText('Gimli')).not.toBeInTheDocument();
    expect(await screen.findByText('Create New Character')).toBeInTheDocument();
    expect(deleted).toBe('char-001');
  });

  it("shows why a section couldn't load in place of its list", async () => {
    const { server } = await import('../../test/mocks/server');
    const { http, HttpResponse } = await import('msw');
    server.use(http.get('/api/characters', () => HttpResponse.error()));
    render(<PlayerDashboard />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Could not reach the server. Check your connection and try again.');
    expect(screen.queryByText('Create New Character')).not.toBeInTheDocument();
    expect(await screen.findByText('Test Campaign')).toBeInTheDocument();
  });

  it('opens the delete dialog with focus in the described name field, and closes it on Escape', async () => {
    render(<PlayerDashboard />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole('button', { name: 'Delete character' }));

    const nameField = screen.getByRole('textbox', { name: 'Character name' });
    expect(nameField).toHaveFocus();
    expect(nameField).toHaveAccessibleDescription("Type the character's name below to confirm.");
    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Delete character' })).toHaveFocus();
  });

  it('closes delete modal on Cancel', async () => {
    render(<PlayerDashboard />);
    await waitFor(() => screen.getByText('Gimli'));
    const user = userEvent.setup();
    await user.click(screen.getByTitle('Delete character'));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Cancel' }));
    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });
});
