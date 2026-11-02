import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import Button from './Button';
import Input from './Input';
import Modal from './Modal';

/** A page with a button that opens the dialog, and buttons on either side of it to tab to. */
function Page({ onClose = () => {}, dismissible, autoFocusNotes = false }: {
  onClose?: () => void;
  dismissible?: boolean;
  autoFocusNotes?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const close = () => { onClose(); setOpen(false); };
  return (
    <>
      <button type="button">Before</button>
      <button type="button" onClick={() => setOpen(true)}>Edit notes</button>
      {open && (
        <Modal
          title="Session Notes"
          subtitle="Only the DM sees these"
          onClose={close}
          dismissible={dismissible}
          footer={(
            <>
              <Button variant="secondary" onClick={close}>Cancel</Button>
              <Button>Save</Button>
            </>
          )}
        >
          <Input label="Notes" autoFocus={autoFocusNotes} />
        </Modal>
      )}
      <button type="button">After</button>
    </>
  );
}

async function openDialog(props: Parameters<typeof Page>[0] = {}) {
  const user = userEvent.setup();
  render(<Page {...props} />);
  await user.click(screen.getByRole('button', { name: 'Edit notes' }));
  return { user, dialog: screen.getByRole('dialog') };
}

describe('Modal', () => {
  it('is a modal dialog named by its title and described by its subtitle', async () => {
    const { dialog } = await openDialog();

    expect(dialog).toHaveAccessibleName('Session Notes');
    expect(dialog).toHaveAccessibleDescription('Only the DM sees these');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
  });

  it('moves focus to its first control, the close button, when it opens', async () => {
    await openDialog();

    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
  });

  it('leaves focus on a field that takes it with autoFocus', async () => {
    await openDialog({ autoFocusNotes: true });

    expect(screen.getByLabelText('Notes')).toHaveFocus();
  });

  it('wraps Tab from its last control back to the first', async () => {
    const { user } = await openDialog();

    await user.tab();
    expect(screen.getByLabelText('Notes')).toHaveFocus();
    await user.tab();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();
    await user.tab();

    expect(screen.getByRole('button', { name: 'Close' })).toHaveFocus();
  });

  it('wraps Shift+Tab from its first control to the last', async () => {
    const { user } = await openDialog();

    await user.tab({ shift: true });
    expect(screen.getByRole('button', { name: 'Save' })).toHaveFocus();
    await user.tab({ shift: true });

    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
  });

  it('never lets Tab or Shift+Tab reach the page behind it', async () => {
    const { user, dialog } = await openDialog();

    for (let i = 0; i < 6; i++) {
      await user.tab();
      expect(dialog).toContainElement(document.activeElement as HTMLElement);
    }
    for (let i = 0; i < 6; i++) {
      await user.tab({ shift: true });
      expect(dialog).toContainElement(document.activeElement as HTMLElement);
    }
  });

  it('closes on Escape', async () => {
    const onClose = vi.fn();
    const { user } = await openDialog({ onClose });

    await user.keyboard('{Escape}');

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('closes on a click on the backdrop, but not on a click inside it', async () => {
    const onClose = vi.fn();
    const { user, dialog } = await openDialog({ onClose });

    await user.click(screen.getByText('Only the DM sees these'));
    expect(onClose).not.toHaveBeenCalled();
    await user.click(dialog.parentElement!);

    expect(onClose).toHaveBeenCalledTimes(1);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('ignores Escape, the backdrop and the close button while it is not dismissible', async () => {
    const onClose = vi.fn();
    const { user, dialog } = await openDialog({ onClose, dismissible: false });

    await user.keyboard('{Escape}');
    await user.click(dialog.parentElement!);

    expect(screen.getByRole('button', { name: 'Close' })).toBeDisabled();
    expect(onClose).not.toHaveBeenCalled();
    expect(dialog).toBeInTheDocument();
  });

  it('returns focus to the button that opened it when it closes', async () => {
    const { user } = await openDialog();
    expect(screen.getByRole('button', { name: 'Edit notes' })).not.toHaveFocus();

    await user.keyboard('{Escape}');

    expect(screen.getByRole('button', { name: 'Edit notes' })).toHaveFocus();
  });

  it('returns focus to the opener after a button inside closes it', async () => {
    const { user } = await openDialog({ autoFocusNotes: true });

    await user.click(screen.getByRole('button', { name: 'Cancel' }));

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Edit notes' })).toHaveFocus();
  });
});
