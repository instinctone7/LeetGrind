import { createContext, useCallback, useContext, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiError, practiceApi } from './api'
import type { LeetCodePreview, LinkStatus } from './api'

type PublicProfile = {
  link: LinkStatus | null
  snapshot: LeetCodePreview | null
  newAcceptances: LeetCodePreview['recentSubmissions']
  error: string
  refresh: (link?: LinkStatus) => Promise<void>
  publish: (snapshot: LeetCodePreview | null) => void
  updateLink: (link: LinkStatus) => void
}

const PublicProfileContext = createContext<PublicProfile | null>(null)

export function PublicLeetCodeProvider({ accountId, children }: { accountId: string; children: ReactNode }) {
  const [snapshot, setSnapshot] = useState<LeetCodePreview | null>(null)
  const [link, setLink] = useState<LinkStatus | null>(null)
  const [newAcceptances, setNewAcceptances] = useState<LeetCodePreview['recentSubmissions']>([])
  const latestSnapshot = useRef<LeetCodePreview | null>(null)
  const [error, setError] = useState('')
  const revision = useRef(0)
  const updateLink = useCallback((next: LinkStatus) => {
    revision.current += 1
    setLink(next)
  }, [])

  const publish = useCallback((next: LeetCodePreview | null) => {
    revision.current += 1
    latestSnapshot.current = next
    setNewAcceptances([])
    setSnapshot(next)
    setError('')
  }, [])

  const lastReturnSync = useRef(0)
  const refresh = useCallback(async (chosen?: LinkStatus) => {
    const request = ++revision.current
    let syncError = ''
    try {
      const link = chosen ?? await practiceApi.status(accountId)
      if (request !== revision.current) return
      setLink(link)
      if (!link.previewEnabled || !link.handle || link.fixture || link.state === 'DISCONNECTED') {
        latestSnapshot.current = null
        setNewAcceptances([])
        setSnapshot(null)
        setError(!link.previewEnabled && link.state === 'VERIFIED' && !link.fixture
          ? 'LeetCode sync is disabled on this deployment. Solves and quest XP are waiting for sync to be enabled.'
          : '')
        return
      }
      if (link.syncAvailable) {
        lastReturnSync.current = Date.now()
        try {
          const synced = await practiceApi.sync(accountId)
          if (request !== revision.current) return
          setLink(synced)
        } catch (failure) {
          syncError = failure instanceof ApiError ? failure.message : 'LeetCode activity could not be synced.'
        }
      }
      if (request !== revision.current) return
      const next = await practiceApi.preview(accountId)
      if (request === revision.current) {
        const previous = latestSnapshot.current
        if (previous && next && previous.username.toLowerCase() === next.username.toLowerCase()) {
          const seen = new Set(previous.recentSubmissions.map(item => item.titleSlug))
          const latest = Math.max(0, ...previous.recentSubmissions.map(item => item.timestamp))
          const additions = next.recentSubmissions.filter(item => {
            if (item.timestamp < latest || seen.has(item.titleSlug)) return false
            seen.add(item.titleSlug)
            return true
          })
          setNewAcceptances(additions.slice(0, 3))
        }
        latestSnapshot.current = next
        setSnapshot(next)
        setError(syncError)
      }
    } catch (failure) {
      if (request === revision.current) {
        setError(syncError || (failure instanceof ApiError ? failure.message : 'The public LeetCode profile could not be refreshed.'))
      }
    }
  }, [accountId])

  useEffect(() => {
    void refresh()
    async function syncOnReturn() {
      if (document.visibilityState !== 'visible' || Date.now() - lastReturnSync.current < 30_000) return
      lastReturnSync.current = Date.now()
      try {
        const status = await practiceApi.status(accountId)
        if (!status.fixture && status.syncAvailable) await refresh(status)
      } catch {
        // The manual refresh can report a failed status request.
      }
    }
    window.addEventListener('focus', syncOnReturn)
    document.addEventListener('visibilitychange', syncOnReturn)
    return () => {
      revision.current += 1
      window.removeEventListener('focus', syncOnReturn)
      document.removeEventListener('visibilitychange', syncOnReturn)
    }
  }, [accountId, refresh])

  return <PublicProfileContext.Provider value={{ link, snapshot, newAcceptances, error, refresh, publish, updateLink }}>
    {children}
  </PublicProfileContext.Provider>
}

export function usePublicLeetCode() {
  const value = useContext(PublicProfileContext)
  if (!value) throw new Error('LeetCode profile data needs a workspace.')
  return value
}

export function PublicLeetCodeStats({ snapshot }: { snapshot: LeetCodePreview }) {
  const { problemsSolved, heatmap } = snapshot
  const days = heatmap.activityDays.slice(-7).reverse()
  return <>
    <p className="muted">@{snapshot.username} · Public LeetCode data</p>
    <dl className="analytics-metrics">
      <div><dt>Problems solved</dt><dd>{problemsSolved.all.toLocaleString()}</dd></div>
      <div><dt>Active days</dt><dd>{heatmap.totalActiveDays.toLocaleString()}</dd></div>
      <div><dt>Reported streak</dt><dd>{heatmap.streak.toLocaleString()}</dd></div>
    </dl>
    <p>{problemsSolved.easy} Easy · {problemsSolved.medium} Medium · {problemsSolved.hard} Hard</p>
    {days.length > 0 && <details>
      <summary>Recent activity on LeetCode</summary>
      <ul className="group-list">{days.map(day => <li key={day.date}>{day.date} · {day.submissions} submissions</li>)}</ul>
    </details>}
  </>
}
