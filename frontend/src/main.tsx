import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { createRoot } from 'react-dom/client'
import { accountApi, ApiError } from './api'
import type { Profile } from './api'
import { Workspace } from './Workspace'
import { ProfileLeetCode } from './ProfilePreview'
import { PublicLeetCodeProvider } from './PublicLeetCode'
import { Wordmark } from './Icons'
import './styles.css'
import './orbital.css'

type Mode = 'signin' | 'register'
type Notice = { text: string; error?: boolean }

function errorNotice(error: unknown): Notice {
  if (error instanceof ApiError) {
    return { text: error.message, error: true }
  }
  return { text: 'LeetGrind could not be reached. Check your connection and try again.', error: true }
}

function App() {
  const [mode, setMode] = useState<Mode>('signin')
  const [profile, setProfile] = useState<Profile | null>(null)
  const [email, setEmail] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const [verificationOnboarding, setVerificationOnboarding] = useState(false)
  const pendingVerification = useRef<string | null>(null)

  function showProfile(next: Profile) {
    setProfile(next)
    setDisplayName(next.displayName)
  }

  useEffect(() => {
    accountApi.restore().then(showProfile).catch(error => {
      if (!(error instanceof ApiError) || error.status !== 401) setNotice(errorNotice(error))
    }).finally(() => setLoading(false))
  }, [])

  async function submitAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    const credentials = { email, password: String(data.get('password') ?? '') }
    setBusy(true)
    setNotice(null)
    try {
      if (mode === 'register') {
        const created = await accountApi.register({
          ...credentials,
          leetcodeHandle: String(data.get('leetcodeHandle') ?? '').trim(),
          displayName: String(data.get('displayName') ?? ''),
        })
        pendingVerification.current = created.id
        try {
          sessionStorage.setItem('leetgrind:verify:' + created.id, 'pending')
        } catch {
          // Verification guidance can still use the in-page registration result.
        }
        setMode('signin')
        setNotice({ text: 'Your account is ready. Sign in to verify your LeetCode profile.' })
      } else {
        const signedIn = await accountApi.login(credentials)
        const onboardingKey = 'leetgrind:verify:' + signedIn.id
        let guide = pendingVerification.current === signedIn.id
        try {
          guide ||= sessionStorage.getItem(onboardingKey) === 'pending'
          sessionStorage.removeItem(onboardingKey)
        } catch {
          // A blocked storage API should not interrupt signing in.
        }
        if (pendingVerification.current === signedIn.id) pendingVerification.current = null
        setVerificationOnboarding(guide)
        showProfile(signedIn)
      }
    } catch (error) {
      setNotice(errorNotice(error))
    } finally {
      setBusy(false)
    }
  }

  async function saveProfile(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!profile) return
    setBusy(true)
    setNotice(null)
    try {
      showProfile(await accountApi.updateProfile(profile.id, displayName))
      setNotice({ text: 'Profile saved.' })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) setProfile(null)
      setNotice(errorNotice(error))
    } finally {
      setBusy(false)
    }
  }

  async function deleteAccount(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!profile) return
    const password = String(new FormData(event.currentTarget).get('password') ?? '')
    setBusy(true)
    setNotice(null)
    try {
      await accountApi.deleteAccount(profile.id, password)
      setProfile(null)
      setEmail('')
      setMode('signin')
      setNotice({ text: 'Your account was deleted.' })
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) setProfile(null)
      setNotice(errorNotice(error))
    } finally {
      setBusy(false)
    }
  }

  async function signOut() {
    setBusy(true)
    setNotice(null)
    try {
      await accountApi.logout()
      setProfile(null)
      setMode('signin')
      setNotice({ text: 'Signed out.' })
    } catch (error) {
      setNotice(errorNotice(error))
    } finally {
      setBusy(false)
    }
  }

  const requestedLeetCode = new URLSearchParams(window.location.search).get('leetcode')?.trim().slice(0, 256) ?? ''
  const leetCodePanel = profile && <ProfileLeetCode key={profile.id + requestedLeetCode} accountId={profile.id}
    requestedUrl={requestedLeetCode} onboarding={verificationOnboarding} />
  const banner = notice && <p className={notice.error ? 'notice error' : 'notice'} role={notice.error ? 'alert' : 'status'}>{notice.text}</p>

  if (profile) return <PublicLeetCodeProvider key={profile.id} accountId={profile.id}>
    <Workspace profile={profile} initialView={requestedLeetCode || verificationOnboarding ? 'profile' : 'overview'} notice={banner} clearNotice={() => setNotice(null)}
    profilePanel={<div className="profile-panel" aria-busy={busy}>
      <h1>Your profile</h1>
      <p className="intro">Your display name and handle identify you in LeetGrind.</p>
      {verificationOnboarding && leetCodePanel}
      <form onSubmit={saveProfile}>
        <label>Display name
          <input name="displayName" value={displayName} required maxLength={80}
            onChange={event => setDisplayName(event.target.value)} disabled={busy} />
        </label>
        <dl className="profile-details">
          <div><dt>Handle</dt><dd>@{profile.handle}</dd></div>
          <div><dt>Email</dt><dd>{profile.email}</dd></div>
        </dl>
        <button type="submit" disabled={busy}>Save profile</button>
      </form>
      {!verificationOnboarding && leetCodePanel}
    </div>}
    settingsPanel={<div className="profile-panel" aria-busy={busy}>
      <h1>Settings</h1>
      <button type="button" className="text-button" onClick={signOut} disabled={busy}>Sign out</button>
      <details className="account-deletion">
        <summary>Delete account</summary>
        <p className="muted">This permanently removes your profile, sessions, practice data, and group scores.
          Transfer or close groups you own first.</p>
        <form onSubmit={deleteAccount}>
          <label>Current password
            <input name="password" type="password" autoComplete="current-password" required maxLength={72} disabled={busy} />
          </label>
          <label className="delete-confirm">
            <input type="checkbox" required disabled={busy} /> I want to permanently delete my account.
          </label>
          <button type="submit" disabled={busy}>Permanently delete account</button>
        </form>
      </details>
    </div>} />
  </PublicLeetCodeProvider>

  return <main className="orbital-auth">
    <header className="auth-topbar"><Wordmark /></header>
    <div className="auth-grid">
      <section className="auth-intro" aria-label="About LeetGrind">
        <p className="deck-kicker">LeetGrind Observatory</p>
        <p className="auth-headline">Practice together.</p>
        <p>Track your coding practice, keep a streak, and climb your group’s leaderboard.</p>
      </section>
      <section className="deck-panel auth-panel" aria-busy={loading || busy}>
        {loading ? <p role="status">Checking your session…</p> : <>
          <h1>{mode === 'signin' ? 'Sign in' : 'Create an account'}</h1>
          <form key={mode} onSubmit={submitAccount}>
            {mode === 'register' && (
              <>
                <label>Display name
                  <input name="displayName" autoComplete="nickname" required maxLength={80} disabled={busy} />
                </label>
                <label>LeetCode username or profile URL
                  <input name="leetcodeHandle" placeholder="https://leetcode.com/u/username/" autoComplete="off"
                    autoCapitalize="none" maxLength={256} required disabled={busy} />
                </label>
              </>
            )}
            <label>Email
              <input name="email" type="email" autoComplete="username" value={email}
                onChange={event => setEmail(event.target.value)} required maxLength={254} disabled={busy} />
            </label>
            <label>Password
              <input name="password" type="password" autoComplete={mode === 'signin' ? 'current-password' : 'new-password'}
                minLength={mode === 'register' ? 12 : undefined} maxLength={72} required
                aria-describedby={mode === 'register' ? 'password-help' : undefined} disabled={busy} />
              {mode === 'register' && <small id="password-help">At least 12 characters.</small>}
            </label>
            <button type="submit" disabled={busy}>{mode === 'signin' ? 'Sign in' : 'Create account'}</button>
          </form>
          <p className="switch">
            {mode === 'signin' ? 'New to LeetGrind?' : 'Already have an account?'}{' '}
            <button type="button" className="text-button" disabled={busy} onClick={() => {
              setNotice(null)
              setMode(mode === 'signin' ? 'register' : 'signin')
            }}>{mode === 'signin' ? 'Create an account' : 'Sign in'}</button>
          </p>
        </>}
        {banner}
      </section>
    </div>
  </main>
}

createRoot(document.getElementById('root')!).render(<App />)
