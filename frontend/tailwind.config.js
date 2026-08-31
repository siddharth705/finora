/** @type {import('tailwindcss').Config} */
export default {
  // Class-based (not media-query-based) so the ThemeContext's explicit Light/Dark/System
  // choice — not just the OS setting — controls which palette applies. See src/index.css
  // for the `:root` / `.dark` variable definitions these all resolve to.
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        // Sidebar / dark surfaces — intentionally not theme-dependent (see index.css comment)
        sidebar: 'var(--color-sidebar)',
        'sidebar-hover': 'var(--color-sidebar-hover)',
        // App background + cards
        bg: 'var(--color-bg)',
        card: 'var(--color-card)',
        border: 'var(--color-border)',
        surface: 'var(--color-surface)',
        // Text — rgb()/<alpha-value> form because these two are the only colors ever used
        // with Tailwind's opacity modifier (e.g. text-ink/60)
        ink: 'rgb(var(--color-ink) / <alpha-value>)',
        muted: 'var(--color-muted)',
        // Brand
        primary: 'rgb(var(--color-primary) / <alpha-value>)',
        'primary-dark': 'var(--color-primary-dark)',
        'primary-light': 'var(--color-primary-light)',
        'on-primary': 'var(--color-on-primary)',
        // Semantic
        success: 'var(--color-success)',
        'success-bg': 'var(--color-success-bg)',
        danger: 'var(--color-danger)',
        'danger-bg': 'var(--color-danger-bg)',
        warning: 'var(--color-warning)',
        'warning-bg': 'var(--color-warning-bg)',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      boxShadow: {
        card: '0 1px 2px rgba(16,24,40,0.04), 0 1px 3px rgba(16,24,40,0.06)',
        soft: '0 4px 24px rgba(16,24,40,0.08)',
      },
      borderRadius: {
        xl2: '1rem',
      },
    },
  },
  plugins: [],
};
