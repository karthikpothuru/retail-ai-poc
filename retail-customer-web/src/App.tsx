import { useState } from 'react'
import { Link, NavLink, Navigate, Route, Routes, useLocation, useNavigate } from 'react-router'
import { errorMessage } from './api'
import { Notice, PreferencesPage, ProfilePage, RegisterPage, SelectPage } from './pages'
import { SessionProvider, useSession } from './session'
import { ChatPage } from './ChatPage'

const pages = [
  { to: '/register', label: 'Register', title: 'Customer registration.', text: 'Create a synthetic customer, then use a local demo session to manage their profile and preferences.' },
  { to: '/select', label: 'Select customer', title: 'Choose a customer.', text: 'Start a session with a registered synthetic customer ID.' },
  { to: '/profile', label: 'Profile', title: 'Your profile.', text: 'Keep the name on your demo customer profile up to date.' },
  { to: '/preferences', label: 'Preferences', title: 'Your choices.', text: 'Manage language, marketing, and consent for the selected demo customer.' },
  { to: '/assistant', label: 'AI Assistant', title: 'AI Assistant', text: 'Ask about registration, products, or current demo inventory.' },
] as const

function PortalLayout() {
  const { session, busy, loading, end } = useSession()
  const [endError, setEndError] = useState<unknown>(null)
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const page = pages.find(({ to }) => to === pathname) ?? pages[0]
  return <div className="portal">
    <a className="skip-link" href="#main">Skip to content</a>
    <header className="topbar">
      <div className="topbar-inner mx-auto flex items-center justify-between">
        <Link to="/register" className="brand"><i aria-hidden="true" className="brand-mark" /><span>Retail <b>Customer Portal</b></span></Link>
        <span className="poc"><i aria-hidden="true" /> Localhost POC</span>
      </div>
    </header>
    <main id="main" className="shell mx-auto">
      <nav aria-label="Customer portal" className="tabs flex">
        {pages.map(({ to, label }) => <NavLink key={to} to={to}>{label}</NavLink>)}
      </nav>
      <div className="workspace">
        <aside className="intro">
          <div className="intro-copy">
            <h1>{page.title}</h1>
            <p>{page.text}</p>
          </div>
          <div className="portal-art" aria-hidden="true"><span /><span /><span /><i /></div>
          <div className="session-box">
            <i className={session ? 'session-dot active' : 'session-dot'} aria-hidden="true" />
            <div><strong>{busy || loading ? 'Checking session' : session ? 'Demo session active' : 'No customer selected'}</strong>
              {session ? <span>Customer ending in {session.customerId.slice(-8)}</span> : <span>Synthetic accounts only</span>}</div>
            {session && <button className="end-button" onClick={async () => {
              setEndError(null)
              try { await end(); navigate('/select') }
              catch (error) { setEndError(error) }
            }}>End session</button>}
          </div>
        </aside>
        <section className="content-panel">
          {endError != null && <Notice kind="error">{errorMessage(endError)}</Notice>}
          <Routes>
            <Route path="/register" element={<RegisterPage />} />
            <Route path="/select" element={<SelectPage />} />
            <Route path="/profile" element={<ProfilePage />} />
            <Route path="/preferences" element={<PreferencesPage />} />
            <Route path="/assistant" element={<ChatPage />} />
            <Route path="*" element={<Navigate to="/register" replace />} />
          </Routes>
        </section>
      </div>
      <footer className="footer"><span>Localhost only <i aria-hidden="true" /> Synthetic customer data</span></footer>
    </main>
  </div>
}

export function App() {
  return <SessionProvider><PortalLayout /></SessionProvider>
}
