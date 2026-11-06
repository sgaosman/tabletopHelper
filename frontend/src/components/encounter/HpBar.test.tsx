import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { makeMockParticipant } from '../../test/mocks/handlers';
import HpBar from './HpBar';

function bar(overrides: Parameters<typeof makeMockParticipant>[3]) {
  const { container } = render(<HpBar participant={makeMockParticipant('p', 'Gimli', 'PLAYER', overrides)} />);
  return container.firstElementChild!.children;
}

describe('HpBar', () => {
  it('fills to the share of HP left, green above half, amber to a quarter and red below', () => {
    const [healthy] = bar({ hpMax: 40, hpCurrent: 30 });
    expect(healthy).toHaveClass('bg-buff');
    expect(healthy).toHaveStyle({ width: '75%' });

    expect(bar({ hpMax: 40, hpCurrent: 20 })[0]).toHaveClass('bg-cls-monk');
    expect(bar({ hpMax: 40, hpCurrent: 10 })[0]).toHaveClass('bg-debuff');
  });

  it('adds temporary HP after the current HP in the wizard colour, within the bar', () => {
    const [current, temp] = bar({ hpMax: 40, hpCurrent: 30, hpTemp: 20 });
    expect(current).toHaveStyle({ width: '75%' });
    expect(temp).toHaveClass('bg-cls-wizard');
    expect(temp).toHaveStyle({ width: '25%' });
  });

  it('shows no temporary HP when there is none', () => {
    expect(bar({ hpMax: 40, hpCurrent: 30 })).toHaveLength(1);
  });
});
