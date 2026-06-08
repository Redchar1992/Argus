import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

// Admin console runs on 5174. Talks to case-service (8084) and tools-service (8083)
// directly by default; in an integrated deployment set VITE_API_BASE to the gateway.
export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5174,
  },
});
