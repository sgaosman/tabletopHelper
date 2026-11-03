import type { Schemas } from '../../../api/schema';

/** A spell on the sheet, as the server stores it in spellsKnown. */
export type SpellEntry = Schemas['KnownSpell'];

/**
 * Sends one of the character's commands and shows how it went on the sheet: the message given, or one made from the
 * result, for a moment on success, or the server's message on failure (D158). Resolves to whether it succeeded.
 */
export type RunCommand = <T>(command: Promise<T>, done?: string | ((result: T) => string)) => Promise<boolean>;
