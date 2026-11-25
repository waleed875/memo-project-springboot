import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { DashboardLive } from './src/dashboard/DashboardLive'
import { LanguageProvider } from './src/i18n/LanguageProvider'
import { ThemeProvider } from './src/theme/ThemeProvider'
import './src/index.css'

// CI-only mount: uses UNMODIFIED original React dashboard modules. This
// bypasses App's setup/login because those require a real running backend.
// HTTP is intercepted solely inside the Playwright browser context.
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <ThemeProvider><LanguageProvider><DashboardLive config={null} /></LanguageProvider></ThemeProvider>
  </StrictMode>,
)
