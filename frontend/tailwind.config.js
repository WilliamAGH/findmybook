/** @type {import('tailwindcss').Config} */
module.exports = {
  content: ["./index.html", "./src/**/*.{svelte,ts,js}"],
  darkMode: ['selector', '[data-theme="dark"]'],
  theme: {
      extend: {
          colors: {
              'linen': {
                  50: '#FDFCFA',
                  100: '#F5F1E8',
                  200: '#EDE8DC',
                  300: '#E3DDD0',
                  400: '#D4CAB7',
                  500: '#C4B8A0',
                  600: '#A89778',
                  700: '#8B7355',
                  800: '#6B5642',
                  900: '#4A3B2E'
              },
              'canvas': {
                  50: '#FAF7F2',
                  100: '#F0E9DA',
                  200: '#E8DCBD',
                  300: '#DECCA0',
                  400: '#D4A574',
                  500: '#C8935A',
                  600: '#B17B45',
                  700: '#8F6236',
                  800: '#6D4A28',
                  900: '#4A321B'
              },
              'anthracite': {
                  50: '#F9F8F7',
                  100: '#E8E6E3',
                  200: '#D1CCC6',
                  300: '#B5AEA6',
                  400: '#948B81',
                  500: '#6D665F',
                  600: '#534E48',
                  700: '#3D3935',
                  800: '#2A2623',
                  900: '#1A1816'
              },
              'sage': {
                  50: '#F7F8F6',
                  100: '#E8EBE4',
                  200: '#D4D9CC',
                  300: '#B8BFB0',
                  400: '#96A188',
                  500: '#768269',
                  600: '#5C6752',
                  700: '#454E3E',
                  800: '#2F352B',
                  900: '#1C1F1A'
              },
              'obsidian': {
                  50: '#F8FAFC',   // slate-50 - near white text
                  100: '#F1F5F9',  // slate-100 - very light backgrounds
                  200: '#E2E8F0',  // slate-200 - light borders
                  300: '#CBD5E1',  // slate-300 - muted borders
                  400: '#94A3B8',  // slate-400 - secondary text
                  500: '#64748B',  // slate-500 - placeholder text
                  600: '#475569',  // slate-600 - dark secondary text
                  700: '#334155',  // slate-700 - borders in dark mode
                  800: '#1E293B',  // slate-800 - elevated surfaces (cards)
                  900: '#0F1419'   // slate-900 - page background
              },
              'slate': {
                  50: '#F8FAFC',   // near white text
                  100: '#F1F5F9',  // very light backgrounds
                  200: '#E2E8F0',  // light borders
                  300: '#CBD5E1',  // muted borders
                  400: '#94A3B8',  // secondary text
                  500: '#64748B',  // placeholder text
                  600: '#475569',  // dark secondary text
                  700: '#334155',  // borders in dark mode
                  800: '#1E293B',  // elevated surfaces (cards)
                  900: '#0F1419'   // page background
              }
          },
          fontFamily: {
              'sans': ['Inter', '-apple-system', 'BlinkMacSystemFont', 'Segoe UI', 'Roboto', 'sans-serif'],
              'heading': ['Poppins', 'Inter', 'sans-serif'],
              'serif': ['Crimson Pro', 'Georgia', 'serif']
          },
          boxShadow: {
              'soft': '0 2px 8px rgba(26, 24, 22, 0.04), 0 1px 2px rgba(26, 24, 22, 0.06)',
              'soft-lg': '0 10px 25px rgba(26, 24, 22, 0.08), 0 4px 10px rgba(26, 24, 22, 0.05)',
              'canvas': '0 4px 12px rgba(212, 165, 116, 0.15)',
              'book': '0 8px 20px rgba(139, 115, 85, 0.2)'
          },
          borderRadius: {
              'book': '0.375rem'
          }
      }
  },
  plugins: [
    require('@tailwindcss/typography'),
    require('@tailwindcss/forms'),
  ],
}
