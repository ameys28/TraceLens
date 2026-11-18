/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      fontFamily: {
        sans: ['"IBM Plex Sans"', 'system-ui', 'sans-serif'],
        mono: ['"IBM Plex Mono"', 'ui-monospace', 'monospace'],
      },
      colors: {
        ink: { 950: '#0b0d10', 900: '#111418', 850: '#161a1f', 800: '#1c2127', 700: '#2a3038', 600: '#3a424c', 500: '#5b6573', 400: '#8a94a3', 300: '#b4bcc8', 200: '#d6dbe2', 100: '#eef1f4' },
        signal: { DEFAULT: '#f5a524', soft: '#f5a52422' },
        bad: { DEFAULT: '#f0565a', soft: '#f0565a22' },
        good: { DEFAULT: '#3ecf8e', soft: '#3ecf8e22' },
      },
    },
  },
  plugins: [],
};
