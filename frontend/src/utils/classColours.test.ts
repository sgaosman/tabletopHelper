import { describe, it, expect } from 'vitest';
import { getClassBg, getClassColour, getParticipantBg, getParticipantColour } from './classColours';

describe('classColours', () => {
  it('points at the theme token for the class, so the colour follows the theme', () => {
    expect(getClassColour('Wizard')).toBe('var(--color-cls-wizard)');
    expect(getClassBg('Wizard')).toBe('var(--color-cls-wizard-bg)');
  });

  it('uses the first class of a multiclass character', () => {
    expect(getClassColour('Fighter / Wizard')).toBe('var(--color-cls-fighter)');
  });

  it('falls back to neutral tokens for a missing or unknown class', () => {
    expect(getClassColour(undefined)).toBe('var(--color-faint)');
    expect(getClassColour('Blood Hunter')).toBe('var(--color-faint)');
    expect(getClassBg(null)).toBe('var(--color-page-alt)');
  });

  it('gives monsters the monster colours', () => {
    expect(getParticipantColour(true, 'Wizard')).toBe('var(--color-monster)');
    expect(getParticipantBg(true)).toBe('var(--color-monster-bg)');
    expect(getParticipantColour(false, 'Bard')).toBe('var(--color-cls-bard)');
  });
});
