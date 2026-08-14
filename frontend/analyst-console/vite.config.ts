import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Browser API traffic remains same-origin. Vite forwards /bff to the Node identity
// BFF, which alone holds the upstream JWT and calls the Java services.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/bff': {
        target: process.env.VITE_BFF_TARGET ?? 'http://127.0.0.1:3001',
        changeOrigin: false,
      },
    },
  },
});
