import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// The dashboard talks to the config server through Vite's dev proxy, which keeps the
// browser on one origin: no CORS setup on the server, and the API key never has to be
// put in a URL for REST calls.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: process.env.CONFIG_SERVER_URL || 'http://localhost:8080',
        changeOrigin: true
      },
      '/ws': {
        target: process.env.CONFIG_SERVER_URL || 'http://localhost:8080',
        ws: true,
        changeOrigin: true
      }
    }
  }
})
