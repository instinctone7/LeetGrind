import { useState } from 'react'
import type { RewardsProgress } from './api'
import { Icon } from './Icons'
import './achievements.css'

const unlockedDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' })
const filters = { all: 'All', progress: 'In progress', unlocked: 'Unlocked' } as const
type Filter = keyof typeof filters
type Achievement = {
  code: string
  name: string
  description?: string
  icon: string
  tone: 'cyan' | 'amber' | 'purple'
  unlockedAt?: string
  completed: number
  target: number
  unit: string
}

function isUnlocked(item: Achievement): item is Achievement & { unlockedAt: string } {
  return Boolean(item.unlockedAt)
}

function achievements(rewards: RewardsProgress): Achievement[] {
  const days = rewards.activity.longestStreak
  const recorded = new Map(rewards.badges.map(badge => [badge.code, badge.unlockedAt]))
  const known = [
    { code: 'FIRST_SOLVE', name: 'First solve', description: 'Complete your first verified problem solve.',
      icon: 'bolt', tone: 'cyan', completed: days > 0 ? 1 : 0, target: 1, unit: 'solved' },
    { code: 'THREE_DAY_STREAK', name: 'Three-day streak', description: 'Solve a problem on three consecutive days.',
      icon: 'local_fire_department', tone: 'amber', completed: Math.min(days, 3), target: 3, unit: 'days' },
  ] as const
  return [
    ...known.map(item => ({ ...item, unlockedAt: recorded.get(item.code) })),
    ...rewards.badges.filter(badge => !known.some(item => item.code === badge.code)).map(badge => ({
      code: badge.code, name: badge.code, icon: 'military_tech', tone: 'purple' as const,
      unlockedAt: badge.unlockedAt, completed: 1, target: 1, unit: '',
    })),
  ]
}

function BadgeProgress({ item }: { item: Achievement }) {
  return <div className="achievement-progress">
    <span>{item.completed} / {item.target} {item.unit}</span>
    <progress value={item.completed} max={item.target} aria-label={item.name + ' progress'} />
  </div>
}

function TrophyCard({ item }: { item: Achievement }) {
  return <li className="achievement-panel achievement-card" data-tone={item.tone}>
    <div className="achievement-card-header">
      <span className="achievement-icon"><Icon name={item.icon} /></span>
      <div><h3>{item.name}</h3>{item.description && <p>{item.description}</p>}</div>
    </div>
    <footer>
      {item.unlockedAt ? <div className="achievement-unlocked">
        <span><Icon name="check" />Unlocked</span>
        <time dateTime={item.unlockedAt}>{unlockedDate.format(new Date(item.unlockedAt))} UTC</time>
      </div> : <BadgeProgress item={item} />}
    </footer>
  </li>
}

export function AchievementBoard({ rewards, error, onPractice }: {
  rewards: RewardsProgress | null
  error: string
  onPractice: () => void
}) {
  const [filter, setFilter] = useState<Filter>('all')
  const badges = rewards ? achievements(rewards) : []
  const unlocked = badges.filter(isUnlocked)
  const pending = badges.filter(item => !isUnlocked(item))
  const inProgress = pending.filter(item => item.completed > 0)
  const next = [...pending].sort((left, right) => right.completed / right.target - left.completed / left.target).slice(0, 3)
  const latest = [...unlocked].sort((left, right) => Date.parse(right.unlockedAt) - Date.parse(left.unlockedAt))[0]
  const sets = { all: badges, progress: inProgress, unlocked }
  const percent = badges.length ? Math.round(unlocked.length / badges.length * 100) : 0

  return <div className="achievement-board">
    <header className="achievement-hero">
      <h1>Achievements</h1>
      <p>Track verified solves and coding milestones.</p>
    </header>
    {error && <p role="alert" className="notice error">{error}</p>}
    {rewards ? <>
      <section className="achievement-panel achievement-summary" aria-label="Achievement summary">
        <div>
          <div className="achievement-summary-count"><strong>{unlocked.length} / {badges.length}</strong><span>Unlocked</span></div>
          <div className="achievement-summary-progress">
            <progress value={unlocked.length} max={badges.length} aria-label="Achievements unlocked" /><strong>{percent}%</strong>
          </div>
        </div>
        <div>
          <strong>{rewards.activity.longestStreak} {rewards.activity.longestStreak === 1 ? 'day' : 'days'}</strong>
          <p>Best coding streak</p>
        </div>
        <div className="achievement-summary-last">
          <span className="achievement-icon"><Icon name="military_tech" /></span>
          <div><strong>{latest?.name ?? 'None yet'}</strong><p>Latest unlock</p></div>
        </div>
      </section>
      {next.length > 0 && <section aria-labelledby="next-milestones">
        <h2 id="next-milestones">Next milestones</h2>
        <ul className="achievement-grid">
          {next.map(item => <li className="achievement-panel achievement-next" data-tone={item.tone} key={item.code}>
            <h3>{item.name}</h3>
            <BadgeProgress item={item} />
            <button type="button" className="text-button" onClick={onPractice}>View practice <Icon name="arrow_forward" /></button>
          </li>)}
        </ul>
      </section>}
      <section className="achievement-collection" aria-label="Badges">
        <div className="achievement-filters" role="group" aria-label="Filter badges">
          {(Object.keys(filters) as Filter[]).map(option => <button type="button" key={option} aria-pressed={option === filter}
            onClick={() => setFilter(option)}>{filters[option]} ({sets[option].length})</button>)}
        </div>
        {sets[filter].length > 0 ? <ul className="achievement-grid">{sets[filter].map(item => <TrophyCard key={item.code} item={item} />)}</ul>
          : <p className="achievement-panel achievement-empty" role="status">
            {filter === 'unlocked' ? 'No achievements unlocked yet.' : 'No achievements in progress.'}
          </p>}
      </section>
    </> : !error && <p role="status">Loading achievements…</p>}
  </div>
}
