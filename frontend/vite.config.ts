import { defineConfig } from 'vite';
import vue from '@vitejs/plugin-vue';

export default defineConfig({
  plugins: [vue()],
  server: {
    proxy: {
      '/api': 'http://localhost:18080',
      '/actuator': 'http://localhost:18080'
    }
  },
  build: {
    outDir: 'dist',
    sourcemap: false
  }
});
