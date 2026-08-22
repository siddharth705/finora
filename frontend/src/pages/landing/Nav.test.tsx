import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { Nav } from './Nav';

describe('Nav', () => {
  it('renders transparent, inverted styling when overHero is true', () => {
    render(
      <MemoryRouter>
        <Nav overHero={true} />
      </MemoryRouter>
    );
    const header = screen.getByRole('banner');
    expect(header.style.background).not.toContain('255, 255, 255');
    expect(header.style.backdropFilter === 'none' || header.style.backdropFilter === '').toBe(true);
    expect(screen.getByText('Finora')).toHaveStyle({ color: '#F8FAFC' });
  });

  it('renders the translucent-glass look when overHero is false', () => {
    render(
      <MemoryRouter>
        <Nav overHero={false} />
      </MemoryRouter>
    );
    const header = screen.getByRole('banner');
    expect(header.style.background).toContain('255, 255, 255');
    expect(screen.getByText('Finora')).toHaveStyle({ color: '#0F172A' });
  });

  it('gives the open mobile menu panel an explicit dark background while overHero', () => {
    render(
      <MemoryRouter>
        <Nav overHero={true} />
      </MemoryRouter>
    );
    const menuButton = screen.getByRole('button', { name: 'Open menu' });
    fireEvent.click(menuButton);
    const panelLinks = screen.getAllByText('How it works');
    // The mobile panel's own instance -- the desktop <nav> renders one too (hidden via CSS, not
    // the DOM), so the panel is whichever instance sits inside the border-t px-5 py-2 container.
    const panel = panelLinks.map((el) => el.closest('div.border-t')).find(Boolean);
    expect(panel).toBeTruthy();
    expect(panel?.getAttribute('style')).toContain('background');
  });
});
