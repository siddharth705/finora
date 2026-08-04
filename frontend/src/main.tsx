import React from 'react';
import ReactDOM from 'react-dom/client';
import App from './App';
import { initMonitoring } from './lib/monitoring';
import './index.css';

// Before render, so an error thrown during the very first mount is still captured. A no-op unless
// VITE_SENTRY_DSN is set, which is why it is safe to call unconditionally here.
initMonitoring();

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>
);
