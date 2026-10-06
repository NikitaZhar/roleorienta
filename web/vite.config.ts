import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Разработка: SPA и API на одном origin — Vite проксирует /api на job-api (технический документ §16.17:
// same-origin, cookie сессии SameSite=Lax, CSRF-токен в cookie XSRF-TOKEN).
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
});
