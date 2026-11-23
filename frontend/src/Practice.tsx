import { useEffect, useState } from 'react'
import { ApiError, practiceApi, rewardsApi } from './api'
import type { CatalogProblem, Progress, RewardsProgress, XpEntry } from './api'
import { PracticeDeck } from './PracticeDeck'
import { usePublicLeetCode } from './PublicLeetCode'

const rewardNames: Record<string, string> = {
  DAILY_LOGIN: 'Daily login',
  FEATURED: 'Problem of the Day bonus',
  DAILY_QUEST: 'Daily quest bonus',
  WEEKLY_QUEST: 'Weekly quest bonus',
}

function errorText(error: unknown) {
  return error instanceof ApiError ? error.message : 'LeetGrind could not be reached. Try again.'
}

const dateFormat = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' })
function date(value: string | null) {
  return value ? dateFormat.format(new Date(value)) + ' UTC' : '—'
}

export function Practice({ accountId }: { accountId: string }) {
  const { snapshot, error: previewError, refresh } = usePublicLeetCode()
  const [refreshingPreview, setRefreshingPreview] = useState(false)
  const [progress, setProgress] = useState<Progress | null>(null)
  const [entries, setEntries] = useState<XpEntry[]>([])
  const [ledgerRevision, setLedgerRevision] = useState<string | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [rewards, setRewards] = useState<RewardsProgress | null>(null)
  const [catalog, setCatalog] = useState<CatalogProblem[] | null>(null)
  const [catalogError, setCatalogError] = useState('')
  const [catalogRetry, setCatalogRetry] = useState(0)

  useEffect(() => {
    let active = true
    practiceApi.catalog(accountId, 'live')
      .then(problems => {
        if (active) {
          setCatalog(problems)
          setCatalogError('')
        }
      })
      .catch(failure => { if (active) setCatalogError(errorText(failure)) })
    return () => { active = false }
  }, [accountId, ledgerRevision, catalogRetry])

  useEffect(() => {
    let active = true
    let pending = false
    async function loadRewards() {
      if (pending) return
      pending = true
      try {
        const result = await rewardsApi.progress(accountId, 'live')
        if (active) {
          setRewards(result)
          setLedgerRevision(result.ledgerRevision)
        }
      } catch (failure) {
        if (active) setError(errorText(failure))
      } finally {
        pending = false
      }
    }
    void loadRewards()
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void loadRewards()
    }, 4000)
    return () => { active = false; window.clearInterval(timer) }
  }, [accountId])

  useEffect(() => {
    let active = true
    Promise.all([practiceApi.progress(accountId), practiceApi.history(accountId)])
      .then(([totals, history]) => {
        if (active) {
          setProgress(totals)
          setEntries(history.items.filter(entry => entry.provider !== 'FIXTURE'))
          setCursor(history.nextCursor)
        }
      })
      .catch(failure => { if (active) setError(errorText(failure)) })
    return () => { active = false }
  }, [accountId, ledgerRevision])

  async function perform(task: () => Promise<void>) {
    setBusy(true)
    setError('')
    try {
      await task()
    } catch (failure) {
      setError(errorText(failure))
    } finally {
      setBusy(false)
    }
  }

  const publicProfile = snapshot
  const recent = new Set(publicProfile?.recentSubmissions.map(item => item.titleSlug))
  const problems = catalog ? catalog.map(problem =>
    recent.has(problem.slug) ? { ...problem, solved: true } : problem) : []
  const view = rewards

  return (
    <>
      <PracticeDeck problems={problems}
        loading={!catalog && !catalogError} catalogError={catalogError}
        onRetry={() => { setCatalogError(''); setCatalogRetry(retry => retry + 1) }}
        publicProfile={Boolean(publicProfile)}
        solved={publicProfile?.problemsSolved.all ?? progress?.solvedCount ?? 0}
        level={view?.level.number} streak={publicProfile?.heatmap.streak ?? view?.activity.currentStreak}
        quota={view?.quests.find(task => task.kind === 'WEEKLY_QUEST')} />
      {(snapshot || previewError) && <div className="practice-actions">
        <button type="button" className="text-button" disabled={refreshingPreview} onClick={async () => {
          setRefreshingPreview(true)
          await refresh()
          setRefreshingPreview(false)
        }}>{refreshingPreview ? 'Refreshing…' : 'Refresh public LeetCode activity'}</button>
        {previewError && <small>{previewError}</small>}
      </div>}
      {error && <p className="notice error" role="alert">{error}</p>}
      {!progress ? (!error && <p role="status">Loading practice…</p>) : (
        <section className="practice-card" aria-labelledby="xp-heading">
          <h2 id="xp-heading">XP history</h2>
          <p>{view ? view.totalXp : '—'} XP · {progress.solvedCount} credited solves</p>
          <small>Practice XP includes daily login rewards and verified solve rewards.</small>
          {entries.length ? (
            <ul className="xp-list">
              {entries.map(entry => (
                <li key={entry.id}>
                  <div><strong>{entry.reason === 'BASE_SOLVE' ? entry.problemTitle : rewardNames[entry.reason] ?? entry.reason}</strong><small>
                    {entry.provider === 'FIXTURE' ? 'Demo · ' : ''}
                    {entry.reason === 'BASE_SOLVE' && entry.difficulty ? entry.difficulty.toLowerCase() + ' · ' : ''}{date(entry.effectiveAt)}
                  </small></div>
                  <span>+{entry.amount} XP</span>
                </li>
              ))}
            </ul>
          ) : <p className="muted">No XP entries yet.</p>}
          {cursor && <button type="button" className="text-button" disabled={busy} onClick={() => void perform(async () => {
            const next = await practiceApi.history(accountId, cursor)
            setEntries(current => [...current, ...next.items.filter(item => item.provider !== 'FIXTURE' && !current.some(entry => entry.id === item.id))])
            setCursor(next.nextCursor)
          })}>Load more</button>}
        </section>
      )}
    </>
  )
}
