import { useState } from 'react'
import type { ReactNode } from 'react'
import type { Standings, StandingsPeriod } from './api'
import { Avatar, Icon } from './Icons'
import './leaderboard.css'

type Row = Standings['items'][number]
const number = new Intl.NumberFormat()
const periods: { value: StandingsPeriod; label: string }[] = [
  { value: 'weekly', label: 'Weekly' },
  { value: 'monthly', label: 'Monthly' },
  { value: 'all-time', label: 'All time' },
]

function ScoreBar({ xp, leaderXp, label = number.format(xp) + ' XP' }: { xp: number; leaderXp: number; label?: string }) {
  const percent = leaderXp > 0 ? Math.max(0, Math.round(100 * xp / leaderXp)) : 0
  return <div className="ranking-score">
    <div><strong>{label}</strong><span>{leaderXp > 0 ? `${percent}% of leader` : 'No XP yet'}</span></div>
    <span className="ranking-track" aria-hidden="true"><span style={{ width: `${percent}%` }} /></span>
  </div>
}

function Podium({ rows, accountId }: { rows: Row[]; accountId: string }) {
  const leaderXp = rows[0].xp
  return <section aria-label="Podium">
    <ol className="ranking-podium">
      {rows.slice(0, 3).map(row => <li className="ranking-podium-card" data-rank={leaderXp > 0 ? row.rank : undefined} key={row.id}>
        <div className="ranking-podium-badges">
          <span className="ranking-rank"><Icon name="trophy" /> Rank {row.rank}</span>
          {row.id === accountId && <span className="ranking-you">You</span>}
        </div>
        <Avatar name={row.displayName} />
        <div className="ranking-podium-member">
          <strong>{row.displayName}</strong><small>@{row.handle}</small>
        </div>
        <p className="ranking-podium-xp">{number.format(row.xp)} <span>XP</span></p>
        <ScoreBar xp={row.xp} leaderXp={leaderXp} label="XP progress" />
      </li>)}
    </ol>
  </section>
}

export function LeaderboardStandings({ groupName, accountId, page, period, onPeriod, actions, periodLabel, loadMore, featured, busy, error }: {
  groupName?: string
  accountId: string
  page: Standings | null
  period: StandingsPeriod
  onPeriod: (period: StandingsPeriod) => void
  actions: ReactNode
  periodLabel: string
  loadMore: ReactNode
  featured: ReactNode
  busy: boolean
  error: string
}) {
  const [filter, setFilter] = useState('')
  const entries = page?.items ?? []
  const search = filter.trim().toLowerCase().replace(/^@/, '')
  const matches = entries.filter(row => row.displayName.toLowerCase().includes(search) || row.handle.toLowerCase().includes(search))
  const leaderXp = entries[0]?.xp ?? 0

  return <div className="ranking-page">
    <header className="deck-panel ranking-hero">
      <div className="ranking-hero-icon"><Icon name="bar_chart" /></div>
      <div>
        <h1>{groupName ? 'Group Rankings' : 'Individual Rankings'}</h1>
        <p>{groupName ? `${groupName} · XP earned during membership` : 'Your XP and the XP of people in your groups'}</p>
      </div>
      {page && <p className="ranking-window">{periodLabel}</p>}
    </header>

    {entries.length > 0 && <Podium rows={entries} accountId={accountId} />}

    <div className="ranking-controls">
      <div className="ranking-periods" role="group" aria-label="Standings period">
        {periods.map(item => <button type="button" key={item.value} aria-pressed={item.value === period}
          disabled={busy} onClick={() => onPeriod(item.value)}>{item.label}</button>)}
      </div>
      <label className="ranking-filter">
        <Icon name="search" />
        <span className="visually-hidden">Filter loaded members</span>
        <input type="search" placeholder="Filter by name or handle" value={filter}
          onChange={event => setFilter(event.target.value)} />
      </label>
    </div>

    <section className="ranking-standings" aria-labelledby="standings-heading" aria-busy={busy}>
      <header className="ranking-table-header">
        <h2 id="standings-heading"><Icon name="bar_chart" />{periods.find(item => item.value === period)?.label} standings</h2>
        {actions}
      </header>
      {error && <p className="notice error" role="alert">{error}</p>}
      {!page && !error && <p className="ranking-empty" role="status">Loading standings…</p>}
      {page && <>
        {matches.length > 0 ? <table>
          <thead><tr><th scope="col">Rank</th><th scope="col">Member</th><th scope="col">XP progress</th></tr></thead>
          <tbody>{matches.map(row => <tr key={row.id} data-rank={leaderXp > 0 ? row.rank : undefined}
            data-self={row.id === accountId}>
            <td><span className="ranking-place">{row.rank}</span></td>
            <th scope="row">
              <div className="ranking-member">
                <Avatar name={row.displayName} />
                <div><strong>{row.displayName}</strong> {row.id === accountId && <span className="ranking-you">You</span>}
                  <small>@{row.handle}</small></div>
              </div>
            </th>
            <td><ScoreBar xp={row.xp} leaderXp={leaderXp} /></td>
          </tr>)}</tbody>
        </table> : <p className="ranking-empty" role="status">{search ? 'No loaded members match your filter.' : 'No standings for this period.'}</p>}
        <footer className="ranking-table-footer">
          <span>{search ? `${number.format(matches.length)} of ${number.format(entries.length)} loaded` : `${number.format(entries.length)} members shown`}</span>
          {loadMore}
          {featured}
        </footer>
      </>}
    </section>
  </div>
}
