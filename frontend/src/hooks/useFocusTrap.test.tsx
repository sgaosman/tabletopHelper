import { describe, it, expect, vi } from 'vitest';
import { StrictMode, useRef, useState, type ReactNode } from 'react';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useFocusTrap } from './useFocusTrap';

function Trap({ name, onEscape, children }: { name: string; onEscape?: () => void; children?: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  useFocusTrap(ref, onEscape);
  return <div ref={ref} role="dialog" aria-label={name} tabIndex={-1}>{children}</div>;
}

/** A button that opens a trap, which Escape closes. */
function Opener({ children }: { children: ReactNode }) {
  const [open, setOpen] = useState(false);
  return (
    <>
      <button type="button" onClick={() => setOpen(true)}>Open</button>
      {open && <Trap name="Trap" onEscape={() => setOpen(false)}>{children}</Trap>}
    </>
  );
}

describe('useFocusTrap', () => {
  it('answers keys only in the most recent of two open traps', async () => {
    const user = userEvent.setup();
    const escapeFirst = vi.fn();
    const escapeSecond = vi.fn();
    render(
      <>
        <Trap name="First" onEscape={escapeFirst}><button type="button">One</button></Trap>
        <Trap name="Second" onEscape={escapeSecond}>
          <button type="button">Two</button>
          <button type="button">Three</button>
        </Trap>
      </>,
    );
    expect(screen.getByRole('button', { name: 'Two' })).toHaveFocus();

    await user.tab();
    await user.tab();
    expect(screen.getByRole('button', { name: 'Two' })).toHaveFocus();
    await user.keyboard('{Escape}');

    expect(escapeSecond).toHaveBeenCalledTimes(1);
    expect(escapeFirst).not.toHaveBeenCalled();
  });

  it('brings focus back in on Tab after the focused element disappears', async () => {
    const user = userEvent.setup();
    function Steps() {
      const [step, setStep] = useState(1);
      // The keys stop React reusing the focused button for the next step
      return (
        <Trap name="Steps">
          {step === 1
            ? <button key="next" type="button" onClick={() => setStep(2)}>Next</button>
            : <><button key="back" type="button">Back</button><button key="finish" type="button">Finish</button></>}
        </Trap>
      );
    }
    render(<><button type="button">Behind</button><Steps /></>);

    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(document.body).toHaveFocus();
    await user.tab();

    expect(screen.getByRole('button', { name: 'Back' })).toHaveFocus();
  });

  it('sends focus that moves to the page behind back inside', () => {
    render(<><button type="button">Behind</button><Trap name="Trap"><button type="button">Inside</button></Trap></>);

    act(() => screen.getByRole('button', { name: 'Behind' }).focus());

    expect(screen.getByRole('button', { name: 'Inside' })).toHaveFocus();
  });

  it('holds focus itself when nothing inside can take it', async () => {
    const user = userEvent.setup();
    render(<><button type="button">Behind</button><Trap name="Notice"><p>Nothing to press</p></Trap></>);

    expect(screen.getByRole('dialog')).toHaveFocus();
    await user.tab();
    expect(screen.getByRole('dialog')).toHaveFocus();
  });

  it('moves focus in and back out under Strict Mode, which runs effects twice', async () => {
    const user = userEvent.setup();
    render(<StrictMode><Opener><button type="button">Inside</button></Opener></StrictMode>);

    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(screen.getByRole('button', { name: 'Inside' })).toHaveFocus();
    await user.keyboard('{Escape}');

    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Open' })).toHaveFocus();
  });

  it('keeps an autoFocus field focused under Strict Mode, and still returns focus to the opener', async () => {
    const user = userEvent.setup();
    render(<StrictMode><Opener><button type="button">Inside</button><input aria-label="Name" autoFocus /></Opener></StrictMode>);

    await user.click(screen.getByRole('button', { name: 'Open' }));
    expect(screen.getByLabelText('Name')).toHaveFocus();
    await user.keyboard('{Escape}');

    expect(screen.getByRole('button', { name: 'Open' })).toHaveFocus();
  });

  it('leaves focus where it is when a trap closes while focus is in another one', async () => {
    const user = userEvent.setup();
    function TwoTraps() {
      const [firstOpen, setFirstOpen] = useState(true);
      return (
        <>
          {firstOpen && <Trap name="First"><button type="button">One</button></Trap>}
          <Trap name="Second"><button type="button" onClick={() => setFirstOpen(false)}>Close the first</button></Trap>
        </>
      );
    }
    render(<TwoTraps />);

    await user.click(screen.getByRole('button', { name: 'Close the first' }));

    expect(screen.queryByRole('dialog', { name: 'First' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Close the first' })).toHaveFocus();
  });
});
