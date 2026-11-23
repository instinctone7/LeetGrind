import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, practiceApi } from './api'
import type { LeetCodePreview, LinkStatus } from './api'
import { usePublicLeetCode } from './PublicLeetCode'

function statusText(link: LinkStatus, hasPreview: boolean) {
  if (link.state === 'NONE') return 'No profile linked'
  if (link.state === 'DISCONNECTED') return 'Disconnected'
  if (link.state === 'PENDING') return hasPreview ? 'Handle saved · public preview available' : 'Handle saved'
  if (link.providerPaused) return 'Sync paused for maintenance'
  if (!link.syncAvailable && link.syncState !== 'PAUSED') return 'Sync unavailable'
  if (link.syncState === 'QUEUED') return 'Sync queued'
  if (link.syncState === 'RUNNING') return 'Syncing'
  if (link.syncState === 'RETRY') return 'Waiting to retry'
  if (link.syncState === 'PAUSED') return 'Sync paused'
  return 'Ready to sync'
}

function LeetCodeSnapshot({ profile }: { profile: LeetCodePreview }) {
  return <div className="profile-details">
      <h3>{profile.realName || profile.username}</h3>
      <p className="muted">Public LeetCode data · read-only</p>
      <dl>
        <div><dt>Handle</dt><dd>@{profile.username}</dd></div>
        {profile.ranking !== null && <div><dt>LeetCode rank</dt><dd>{profile.ranking.toLocaleString()}</dd></div>}
        <div><dt>Problems solved</dt><dd>{profile.problemsSolved.all} total · {profile.problemsSolved.easy} Easy · {profile.problemsSolved.medium} Medium · {profile.problemsSolved.hard} Hard</dd></div>
        <div><dt>Activity</dt><dd>{profile.heatmap.streak} day streak · {profile.heatmap.totalActiveDays} active days</dd></div>
      </dl>
      {profile.heatmap.activityDays?.length > 0 && <details>
        <summary>Recent coding days</summary>
        <ul className="group-list">{profile.heatmap.activityDays.slice(-7).reverse().map(day =>
          <li key={day.date}>{day.date} · {day.submissions} submissions</li>)}</ul>
      </details>}
      {profile.recentSubmissions.length > 0 && <details open>
        <summary>Recent accepted submissions</summary>
        <ul className="group-list">{profile.recentSubmissions.map((item, index) =>
          <li key={item.titleSlug + ':' + item.timestamp + ':' + index}>
            <span>{item.title}<small>{item.lang}</small></span>
            <span>{new Date(item.timestamp * 1000).toLocaleDateString(undefined, { timeZone: 'UTC' })}</span>
          </li>)}</ul>
      </details>}
    </div>
}

export function ProfileLeetCode({ accountId, requestedUrl }: { accountId: string; requestedUrl: string }) {
  const publicProfile = usePublicLeetCode()
  const { publish } = publicProfile
  const [handle, setHandle] = useState(requestedUrl)
  const [link, setLink] = useState<LinkStatus | null>(null)
  const [profile, setProfile] = useState<LeetCodePreview | null>(null)
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')

  useEffect(() => {
    let active = true
    async function initialize() {
      try {
        const saved = await practiceApi.status(accountId)
        if (!active) return
        setLink(saved)
        const savedHandle = saved.state !== 'DISCONNECTED' && !saved.fixture ? saved.handle : null
        setHandle(requestedUrl || savedHandle || '')
        if (requestedUrl && saved.previewEnabled) {
          const result = await practiceApi.preview(accountId, requestedUrl)
          if (active) {
            setProfile(result)
            if (result && savedHandle === result.username.toLowerCase()) publish(result)
          }
        }
      } catch (failure) {
        if (active) setError(failure instanceof ApiError ? failure.message : 'Could not load the LeetCode profile.')
      } finally {
        if (active) setLoading(false)
      }
    }
    void initialize()
    return () => { active = false }
  }, [accountId, requestedUrl, publish])

  useEffect(() => {
    if (saving || !link || link.fixture || link.state !== 'VERIFIED') return
    if (!link.providerPaused && !['QUEUED', 'RUNNING', 'RETRY'].includes(link.syncState ?? '')) return
    let active = true
    const untilRetry = Date.parse(link.nextSyncAt ?? '') - Date.now()
    const delay = link.providerPaused ? 5000 : link.syncState === 'RETRY' && untilRetry > 0 ? Math.max(1000, Math.min(untilRetry, 30000)) : 1000
    const timer = window.setTimeout(() => {
      practiceApi.status(accountId).then(next => { if (active) setLink(next) })
        .catch(failure => {
          if (active) setError(failure instanceof ApiError ? failure.message : 'Could not check the profile sync.')
        })
    }, delay)
    return () => { active = false; window.clearTimeout(timer) }
  }, [accountId, link, saving])

  async function perform(action: () => Promise<void>) {
    setSaving(true)
    setError('')
    try {
      await action()
    } catch (failure) {
      setError(failure instanceof ApiError ? failure.message : 'Could not update the LeetCode profile.')
    } finally {
      setSaving(false)
    }
  }

  function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    void perform(async () => {
      const saved = await practiceApi.link(accountId, handle)
      setLink(saved)
      setHandle(saved.handle ?? '')
      if (!saved.fixture && profile?.username.toLowerCase() === saved.handle && profile) {
        publish(profile)
      } else {
        setProfile(null)
        publish(null)
        if (saved.previewEnabled && !saved.fixture) {
          const next = await practiceApi.preview(accountId)
          setProfile(next)
          publish(next)
        }
      }
    })
  }

  const linked = link?.fixture ? null : link
  const displayed = requestedUrl ? profile : publicProfile.snapshot
  const message = error || (!requestedUrl ? publicProfile.error : '')
  return <section className="account-deletion" aria-labelledby="account-leetcode-heading">
    <h2 id="account-leetcode-heading">LeetCode profile</h2>
    {!loading && <p role="status">{linked ? statusText(linked, Boolean(displayed)) : 'No profile linked'}{linked?.handle ? ' · ' + linked.handle : ''}</p>}
    <form onSubmit={save}>
      <label>LeetCode handle or profile URL
        <input value={handle} placeholder="https://leetcode.com/u/handle/" maxLength={256} required
          onChange={event => setHandle(event.target.value)} disabled={loading || saving} />
      </label>
      <button type="submit" disabled={loading || saving}>{saving ? 'Saving…' : 'Save LeetCode profile'}</button>
    </form>
    {linked && <div className="practice-actions">
      {linked.syncAvailable && <button type="button" className="text-button" disabled={saving || linked.syncState !== 'IDLE'}
        onClick={() => void perform(async () => { setLink(await practiceApi.sync(accountId)) })}>Sync now</button>}
      {(linked.state === 'VERIFIED' || linked.state === 'PENDING') &&
        <button type="button" className="text-button" disabled={saving} onClick={() => void perform(async () => {
          await practiceApi.disconnect(accountId)
          setHandle('')
          setProfile(null)
          publish(null)
          setLink(await practiceApi.status(accountId))
        })}>Disconnect profile</button>}
    </div>}
    {linked?.lastSuccessfulSyncAt && linked.state === 'VERIFIED' && <p className="muted">Last successful sync ·{' '}
      <time dateTime={linked.lastSuccessfulSyncAt}>{new Date(linked.lastSuccessfulSyncAt).toLocaleString(undefined, { timeZone: 'UTC' })} UTC</time>
    </p>}
    {linked?.coverageUncertain && linked.state === 'VERIFIED' && <p className="muted">Sync coverage is unconfirmed.</p>}
    {loading && <p role="status">Loading LeetCode profile…</p>}
    {message && <p className="notice error" role="alert">{message}</p>}
    {displayed && <LeetCodeSnapshot profile={displayed} />}
  </section>
}
