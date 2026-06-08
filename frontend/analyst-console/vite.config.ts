import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Analyst console runs on 5173. API calls go to the gateway (8080) by default;
// set VITE_API_BASE to point directly at the orchestrator (8082) for standalone dev.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
  },
});
