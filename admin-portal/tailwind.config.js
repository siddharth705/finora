/** @type {import('tailwindcss').Config} */
export default {
  // Same class-based dark mode / semantic color token approach as the user frontend (see
  // src/index.css) -- kept visually consistent as "the same product family," not a re-skin.
  darkMode: 'class',
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        sidebar: 'var(--color-sidebar)',
        'sidebar-hover': 'var(--color-sidebar-hover)',
        bg: 'var(--color-bg)',
        card: 'var(--color-card)',
        border: 'var(--color-border)',
        ink: 'rgb(var(--color-ink) / <alpha-value>)',
        muted: 'var(--color-muted)',
        primary: 'rgb(var(--color-primary) / <alpha-value>)',
        'primary-dark': 'var(--color-primary-dark)',
        'primary-light': 'var(--color-primary-light)',
        'on-primary': 'var(--color-on-primary)',
        success: 'var(--color-success)',
        'success-bg': 'var(--color-success-bg)',
        danger: 'var(--color-danger)',
        'danger-bg': 'var(--color-danger-bg)',
        warning: 'var(--color-warning)',
        'warning-bg': 'var(--color-warning-bg)',
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
        // Reserved for numerals, timestamps, and status codes on data-dense screens (the
        // Operational Dashboard) -- gives figures a fixed-width "instrument panel" feel that
        // sets them apart from prose, without touching the sans-everywhere shared identity
        // elsewhere in the portal (Sidebar, forms, etc. are untouched).
        mono: ['"JetBrains Mono"', 'ui-monospace', 'SFMono-Regular', 'monospace'],
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
