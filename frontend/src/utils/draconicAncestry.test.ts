import { describe, it, expect } from 'vitest';
import { ancestryLabel, ancestryOf, DRACONIC_ANCESTRIES, hasDraconicAncestry } from './draconicAncestry';
import { featureMarker, poolEffect, poolEffectText } from './classFeatures';
import type { ResourcePoolEntry } from '../types/encounter';

describe('draconicAncestry (PHB p.34, D208)', () => {
  it("holds the table's ten dragons, a cone or line each, CON saves against cold and poison", () => {
    expect(DRACONIC_ANCESTRIES.map(a => a.dragon)).toEqual(['Black', 'Blue', 'Brass', 'Bronze', 'Copper', 'Gold',
      'Green', 'Red', 'Silver', 'White']);
    expect(ancestryLabel(ancestryOf('white')!)).toBe('White (cold, 15 ft. cone (CON save))');
    expect(ancestryOf('Black')?.breath).toBe('5 by 30 ft. line (DEX save)');
    expect(ancestryOf('purple')).toBeUndefined();
  });

  it('finds the Draconic Ancestry trait in traits given as an array or as JSON text, and not the FTD ancestries', () => {
    expect(hasDraconicAncestry([{ name: 'Draconic Ancestry', description: '' }])).toBe(true);
    expect(hasDraconicAncestry(JSON.stringify([{ name: 'draconic ancestry' }]))).toBe(true);
    expect(hasDraconicAncestry([{ name: 'Chromatic Ancestry' }])).toBe(false);
    expect(hasDraconicAncestry(null)).toBe(false);
  });
});

describe('racial pool effects (D208)', () => {
  const pool = (effect: string) => ({ poolId: 'race:x', metadata: { effect } as unknown as ResourcePoolEntry['metadata'] });

  it('knows the racial effects, with what a use does', () => {
    expect(poolEffect(pool('breath-weapon'))).toBe('breath-weapon');
    expect(poolEffect(pool('racial-note'))).toBe('racial-note');
    expect(poolEffectText('relentless-endurance')).toMatch(/used by itself/);
    expect(poolEffectText('racial-note')).toBeNull();
  });

  it("names the transformations' and a shift's entries among the conditions", () => {
    expect(featureMarker({ name: 'shifted' })?.label).toBe('Shifted');
    expect(featureMarker({ name: 'Necrotic Shroud' })?.label).toBe('Necrotic Shroud');
    expect(featureMarker({ name: 'radiant soul' })?.description).toMatch(/flying speed of 30 feet/);
  });
});
