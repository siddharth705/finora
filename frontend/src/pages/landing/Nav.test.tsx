import { fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { describe, expect, it } from 'vitest';
import { Nav } from './Nav';

describe('Nav', () => {
  it('renders dark, inverted, non-sticky styling while overHero is true', () => {
    // Regression test: the header must NOT overlap Hero. It sits in normal document flow
    // (position: static) and scrolls away with the page -- there is no scroll position at which
    // it and Hero's own content (the dashboard preview, floating badges) occupy the same screen
    // space, which is what a real reported overlap glitch traced back to.
    render(
      <MemoryRouter>
        <Nav overHero={true} />
      </MemoryRouter>
    );
    const header = screen.getByRole('banner');
    expect(header.style.position).toBe('static');
    expect(header.style.background).not.toContain('255, 255, 255');
    expect(header.style.backdropFilter === 'none' || header.style.backdropFilter === '').toBe(true);
    expect(screen.getByText('Fynora')).toHaveStyle({ color: '#F8FAFC' });
  });

  it('renders the sticky, translucent-glass look when overHero is false', () => {
    render(
      <MemoryRouter>
        <Nav overHero={false} />
      </MemoryRouter>
    );
    const header = screen.getByRole('banner');
    expect(header.style.position).toBe('sticky');
    expect(header.style.top).toBe('0px');
    expect(header.style.background).toContain('255, 255, 255');
    expect(screen.getByText('Fynora')).toHaveStyle({ color: '#0F172A' });
  });

  it('carries a hover class for desktop nav links, with no inline color to shadow it in the glass state', () => {
    // jsdom doesn't apply real stylesheets, so :hover can't be asserted by computed style here --
    // this instead pins the two facts that make the class actually take effect: it's present, and
    // (in the non-overHero state) there's no inline `color` to out-rank it in the cascade. Inline
    // styles always beat class-based :hover rules regardless of specificity, which is exactly the
    // regression this covers -- a hover:text-[#0F172A] class silently made inert by a leftover
    // inline color.
    render(
      <MemoryRouter>
        <Nav overHero={false} />
      </MemoryRouter>
    );
    const link = screen.getByText('How it works');
    expect(link.className).toContain('hover:text-[#0F172A]');
    expect(link.style.color).toBe('');
  });

  it('gives desktop nav links hover feedback in the transparent (overHero) state', () => {
    render(
      <MemoryRouter>
        <Nav overHero={true} />
      </MemoryRouter>
    );
    const link = screen.getByText('How it works');
    fireEvent.mouseEnter(link);
    expect(link).toHaveStyle({ color: '#F8FAFC' });
    fireEvent.mouseLeave(link);
    expect(link).toHaveStyle({ color: 'rgba(248,250,252,0.85)' });
  });

  it('gives the Log in link hover feedback in both overHero states', () => {
    const { rerender } = render(
      <MemoryRouter>
        <Nav overHero={false} />
      </MemoryRouter>
    );
    const loginFalse = screen.getByText('Log in', { selector: 'a' });
    fireEvent.mouseEnter(loginFalse);
    expect(loginFalse).toHaveStyle({ color: '#0F172A' });

    rerender(
      <MemoryRouter>
        <Nav overHero={true} />
      </MemoryRouter>
    );
    const loginTrue = screen.getByText('Log in', { selector: 'a' });
    fireEvent.mouseEnter(loginTrue);
    expect(loginTrue).toHaveStyle({ color: '#F8FAFC' });
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
