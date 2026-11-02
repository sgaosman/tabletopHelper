import { describe, it, expect } from 'vitest';
import { healthLabel, healthStatusLabel } from './healthStatus';

describe('healthStatusLabel', () => {
  it('names each Health Status', () => {
    expect(healthStatusLabel('UNHARMED')).toBe('Unharmed');
    expect(healthStatusLabel('INJURED')).toBe('Injured');
    expect(healthStatusLabel('BLOODIED')).toBe('Bloodied');
    expect(healthStatusLabel('DOWN')).toBe('Down');
  });
});

describe('healthLabel', () => {
  it('shows exact HP when the view carries it', () => {
    expect(healthLabel({ hpCurrent: 5, hpMax: 7, healthStatus: null })).toBe('5/7 HP');
    expect(healthLabel({ hpCurrent: 0, hpMax: 7 })).toBe('0/7 HP');
  });

  it('shows the Health Status when the Player View hides the numbers', () => {
    expect(healthLabel({ hpCurrent: null, hpMax: null, healthStatus: 'BLOODIED' })).toBe('Bloodied');
  });

  it('shows nothing when the view has neither', () => {
    expect(healthLabel({ hpCurrent: null, hpMax: null, healthStatus: null })).toBeNull();
  });
});
