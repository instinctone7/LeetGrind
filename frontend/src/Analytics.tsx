import { useEffect, useState } from 'react'
import { ActivityGrid } from './ActivityGrid'
import { PublicLeetCodeStats, usePublicLeetCode } from './PublicLeetCode'
import { analyticsApi, ApiError } from './api'
import type { AnalyticsPeriod, AnalyticsView } from './api'

type Selection = { period: AnalyticsPeriod; start: string }
const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' })
const number = new Intl.NumberFormat()
const coverageText: Record<AnalyticsView['coverage']['state'], string> = {
  UNAVAILABLE: 'Live LeetCode activity is unavailable. Check your saved profile.',
  NO_LINK: 'Connect a LeetCode profile to see public activity.',
  AWAITING_SYNC: 'The profile is awaiting its first successful sync.',
  PARTIAL: 'Coverage is incomplete. Recent practice may be missing from the recorded history.',
  TRACKED_ONLY: 'This view covers activity recorded after linking.',
  DISCONNECTED: 'The profile is disconnected. Previously recorded practice is still shown here.',
}

function Coverage({ coverage }: { coverage: AnalyticsView['coverage'] }) {
  return <section className="analytics-coverage" aria-labelledby="history-coverage">
    <h2 id="history-coverage">History coverage</h2>
    <p>{coverageText[coverage.state]}</p>
    {!coverage.historicalImportAvailable && <p className="muted">Historical imports are unavailable. Practice totals cover recorded first solves.</p>}
    {(coverage.trackedSince || coverage.lastSuccessfulSyncAt) && <dl className="analytics-times">
      {coverage.trackedSince && <div><dt>First linked</dt><dd><time dateTime={coverage.trackedSince}>
        {dateTime.format(new Date(coverage.trackedSince))} UTC</time></dd></div>}
      {coverage.lastSuccessfulSyncAt && <div><dt>Last successful sync</dt><dd><time dateTime={coverage.lastSuccessfulSyncAt}>
        {dateTime.format(new Date(coverage.lastSuccessfulSyncAt))} UTC</time></dd></div>}
    </dl>}
  </section>
}

function PracticeSummary({ summary, streaks }: Pick<AnalyticsView, 'summary' | 'streaks'>) {
  return <section aria-labelledby="summary-heading">
    <h2 id="summary-heading">Practice summary</h2>
    <p className="muted">{summary.start && summary.end ? summary.start + ' to ' + summary.end + ' (exclusive) UTC' : 'All recorded activity'}</p>
    <dl className="analytics-metrics">
      <div><dt>Problems solved</dt><dd>{number.format(summary.solved)}</dd></div>
      <div><dt>XP earned</dt><dd>{number.format(summary.xpEarned)}</dd></div>
      <div><dt>Coding days</dt><dd>{number.format(summary.codingDays)}</dd></div>
    </dl>
    <p className="muted">Current streak <strong>{streaks.current}</strong> {streaks.current === 1 ? 'day' : 'days'} ·
      Longest streak <strong>{streaks.longest}</strong> {streaks.longest === 1 ? 'day' : 'days'}</p>
    <div className="analytics-breakdown">
      <div>
        <h3>Solves by difficulty</h3>
        <ul className="analytics-counts">{summary.byDifficulty.map(row => <li key={row.difficulty}>
          <span className="difficulty">{row.difficulty.toLowerCase()}</span><strong>{number.format(row.solved)}</strong>
        </li>)}</ul>
      </div>
      <div>
        <h3>Topic practice coverage</h3>
        <p className="muted">A problem can count toward multiple topics.</p>
        {summary.topics.length ? <ul className="analytics-counts">{summary.topics.map(row => <li key={row.topic}>
          <span>{row.topic}</span><strong>{number.format(row.solved)}</strong>
        </li>)}</ul> : <p className="muted">No mapped practice in this period.</p>}
        {summary.unmapped > 0 && <p className="muted">{number.format(summary.unmapped)} {summary.unmapped === 1
          ? 'problem has' : 'problems have'} no topic mapping.</p>}
      </div>
    </div>
  </section>
}

export function Analytics({ accountId }: { accountId: string }) {
  const publicProfile = usePublicLeetCode()
  const [selection, setSelection] = useState<Selection>({ period: 'all-time', start: '' })
  const [view, setView] = useState<AnalyticsView | null>(null)
  const [revision, setRevision] = useState(0)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(true)

  useEffect(() => {
    let keep = true
    analyticsApi.view(accountId, 'live', selection.period, selection.start).then(result => {
      if (keep) {
        setView(result)
      }
    }).catch(failure => {
      if (keep) setError(failure instanceof ApiError ? failure.message : 'Analytics could not be reached. Try again.')
    }).finally(() => { if (keep) setPending(false) })
    return () => { keep = false }
  }, [accountId, selection, revision])

  function begin() {
    setPending(true)
    setError('')
  }

  function select(selected: Selection) {
    begin()
    setSelection(selected)
  }

  const activityView = view
  const shown = activityView?.summary.period === selection.period
    && (!selection.start || activityView.summary.start === selection.start) ? activityView : null

  const snapshot = publicProfile.snapshot
  const activity = snapshot ? snapshot.heatmap.activityDays.map(day => ({ date: day.date, count: day.submissions }))
    : activityView?.dailyActivity?.map(day => ({ date: day.date, count: day.solves })) ?? []

  return <div className="analytics">
    <h1>Practice analytics</h1>
    <div className="analytics-controls">
      <label>Summary period
        <select value={selection.period} onChange={event => select({ period: event.target.value as AnalyticsPeriod, start: '' })}>
          <option value="all-time">All time</option><option value="weekly">Week</option><option value="monthly">Month</option>
        </select>
      </label>
      <button type="button" className="text-button" disabled={pending} onClick={() => {
        begin()
        setRevision(value => value + 1)
        void publicProfile.refresh()
      }}>Refresh analytics</button>
    </div>
    {selection.start && <button type="button" className="text-button" onClick={() => select({ period: selection.period, start: '' })}>
      {selection.period === 'weekly' ? 'Current week' : 'Current month'}
    </button>}
    {error && <p className="notice error" role="alert">{error}</p>}
    {(publicProfile.snapshot || publicProfile.error) && <section aria-labelledby="analytics-leetcode">
      <h2 id="analytics-leetcode">Public LeetCode progress</h2>
      {publicProfile.error && <p role="status">{publicProfile.error}</p>}
      {publicProfile.snapshot && <PublicLeetCodeStats snapshot={publicProfile.snapshot} />}
    </section>}
    {!shown ? pending && <p role="status">Loading analytics…</p> : <>
      <Coverage coverage={shown.coverage} />
      <PracticeSummary summary={shown.summary} streaks={shown.streaks} />
    </>}
    {activityView && <ActivityGrid today={activityView.generatedAt.slice(0, 10)} activity={activity} publicProfile={Boolean(snapshot)}
      onWeek={start => select({ period: 'weekly', start })} />}
    {shown && <section aria-labelledby="recommendations-heading">
        <h2 id="recommendations-heading">Recommended problems</h2>
        {shown.recommendations.length ? <ul className="analytics-recommendations">
          {shown.recommendations.map(item => <li key={item.id}>
            <div>{item.url ? <a href={item.url} target="_blank" rel="noreferrer">{item.title}</a> : <strong>{item.title}</strong>}
              {' · '}<span className="difficulty">{item.difficulty.toLowerCase()}</span></div>
            <small>{item.topics.join(' · ')}</small>
            <p>{item.reason}</p>
          </li>)}
        </ul> : <p className="muted">No unsolved problems are available in this catalog.</p>}
    </section>}
  </div>
}
