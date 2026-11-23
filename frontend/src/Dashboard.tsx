import { useEffect, useState } from 'react'
import type { ReactNode } from 'react'
import { ApiError, groupsApi, practiceApi, rewardsApi } from './api'
import type { Group, GroupFeatured, LeetCodePreview, LinkStatus, Profile, RewardsProgress, Standings, XpEntry } from './api'
import { Avatar, Icon } from './Icons'
import { RewardCountdown } from './QuestBoard'
import { PublicLeetCodeStats, usePublicLeetCode } from './PublicLeetCode'

const fullDate = new Intl.DateTimeFormat(undefined, { weekday: 'long', month: 'long', day: 'numeric', timeZone: 'UTC' })
const dayLabel = new Intl.DateTimeFormat(undefined, { weekday: 'short', timeZone: 'UTC' })
const timeLabel = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'UTC' })
const number = new Intl.NumberFormat()
const xpReasons: Record<string, string> = {
  DAILY_LOGIN: 'Daily login', FEATURED: 'Featured problem bonus', DAILY_QUEST: 'Daily quest bonus', WEEKLY_QUEST: 'Weekly quest bonus',
  DUEL_WIN: 'Duel victory', DUEL_LOSS: 'Duel loss',
}

type DashboardData = { rewards: RewardsProgress; history: XpEntry[]; link: LinkStatus }
type GroupData = { standings: Standings; featured: GroupFeatured }
type Task = RewardsProgress['quests'][number]

function Action({ children, onClick, className = '' }: { children: ReactNode; onClick: () => void; className?: string }) {
  return <button type="button" className={`deck-action ${className}`} onClick={onClick}>{children}</button>
}

function SyncStatus({ link, onProfile }: { link?: LinkStatus; onProfile: () => void }) {
  let label = 'Connect LeetCode'
  if (link?.providerPaused || link?.syncState === 'PAUSED') label = 'Sync paused'
  else if (link?.syncState === 'QUEUED' || link?.syncState === 'RUNNING') label = 'Sync in progress'
  else if (link?.state === 'PENDING') label = 'LeetCode username already linked'
  else if (link?.state === 'VERIFIED' && !link.previewEnabled) label = 'LeetCode sync disabled'
  else if (link?.state === 'VERIFIED' && (link.failureCode || (link.lastSuccessfulSyncAt && link.coverageUncertain))) label = 'Coverage incomplete'
  else if (link?.state === 'VERIFIED' && link.lastSuccessfulSyncAt) label = 'LeetCode synced'
  else if (link?.state === 'VERIFIED') label = 'Ready to sync'
  else if (link?.state === 'DISCONNECTED') label = 'Profile disconnected'
  return <button type="button" className="sync-indicator" onClick={onProfile}>
    <span className="signal-dot" aria-hidden="true" />
    {label}
    {link?.lastSuccessfulSyncAt && <time dateTime={link.lastSuccessfulSyncAt}>{timeLabel.format(new Date(link.lastSuccessfulSyncAt))} UTC</time>}
  </button>
}

function Activity({ profile, history, onPractice }: { profile: Profile; history: XpEntry[]; onPractice: () => void }) {
  const personal = history.filter(item => item.provider !== 'FIXTURE').slice(0, 5)
  return <section className="deck-panel feed-panel" aria-labelledby="overview-feed">
    <header className="deck-header feed-heading">
      <h2 className="deck-kicker" id="overview-feed"><span className="signal-dot signal-green" aria-hidden="true" /> Practice telemetry</h2>
      <Action onClick={onPractice}>XP history <Icon name="arrow_forward" /></Action>
    </header>
    {personal.length ? <ul className="deck-list">
        {personal.map((item, index) => <li key={item.id} className="telemetry-row">
          <Avatar name={profile.displayName} className={`tone-${index % 3}`} />
          <div className="telemetry-detail">
            <strong>{profile.displayName}</strong><span className="muted"> {item.reason === 'BASE_SOLVE' ? 'solved' : 'earned'} </span>
            <span>{item.reason === 'BASE_SOLVE' ? item.problemTitle : xpReasons[item.reason] ?? 'Practice XP'}</span>
            {item.difficulty && <span className={`deck-chip difficulty-${item.difficulty.toLowerCase()}`}>{item.difficulty.toLowerCase()}</span>}
          </div>
          <div className="telemetry-meta"><span className="xp-accent">{item.amount >= 0 ? '+' : ''}{number.format(item.amount)} XP</span>
            <time dateTime={item.effectiveAt}>{timeLabel.format(new Date(item.effectiveAt))}</time></div>
        </li>)}
      </ul> : <EmptyFeed icon="bolt" text="Your recorded practice and rewards will appear here." />}
  </section>
}

function EmptyFeed({ icon, text }: { icon: string; text: string }) {
  return <div className="deck-empty"><Icon name={icon} /><p>{text}</p></div>
}

function Metric({ title, icon, value, unit, children, tone }: {
  title: string; icon: string; value: string; unit: string; children: ReactNode; tone?: 'blue'
}) {
  return <section className={`deck-panel deck-metric ${tone ?? ''}`}>
    <div className="deck-header"><h2 className="deck-kicker">{title}</h2><Icon name={icon} /></div>
    <div className="metric-value"><strong>{value}</strong><span>{unit}</span></div>
    <div className="metric-note">{children}</div>
  </section>
}

function Featured({ feature, team, onPractice }: {
  feature?: RewardsProgress['featured']; team?: GroupFeatured; onPractice: () => void
}) {
  return <section className="deck-panel feature-panel" aria-labelledby="overview-featured">
    <header className="deck-header">
      <div className="deck-cluster"><h2 id="overview-featured" className="deck-kicker">Problem of the day</h2>
        {feature && <span className="deck-chip bonus-chip">+{feature.task.rewardXp} XP bonus</span>}</div>
      {feature && <RewardCountdown endsAt={feature.task.endsAt} className="deck-muted-code" />}
    </header>
    {feature ? <>
      <div className="deck-cluster feature-title">
        <h3>{feature.title}</h3><span className={`deck-chip difficulty-${feature.difficulty.toLowerCase()}`}>{feature.difficulty.toLowerCase()}</span>
      </div>
      <div className="deck-cluster">{feature.topics.map(topic => <span key={topic} className="deck-chip topic-chip">{topic}</span>)}</div>
      <p>Earn bonus XP for your first accepted solve within today’s practice window.</p>
      <div className="feature-window"><Icon name="event" /><span>Sync cutoff</span>
        <time dateTime={feature.task.cutoffAt}>{timeLabel.format(new Date(feature.task.cutoffAt))} UTC</time></div>
      <div className="feature-actions">
        {feature.url && <a className="deck-primary" href={feature.url} target="_blank" rel="noreferrer">
          Solve on LeetCode <Icon name="open_in_new" /></a>}
        <Action onClick={onPractice} className={feature.url ? 'deck-secondary' : 'deck-primary'}>View practice</Action>
      </div>
      {team && <small className="deck-cluster"><Icon name="group" />{team.completed} of {team.members} group members completed</small>}
    </> : <EmptyFeed icon="code" text="The featured problem will appear here." />}
  </section>
}

function questLabel(task: Task) {
  if (task.kind === 'FEATURED') return 'Complete the featured problem'
  if (task.kind === 'WEEKLY_QUEST') return `Solve ${task.target} distinct new problems this week`
  return `Solve ${task.target} ${task.topic ?? 'new'} ${task.target === 1 ? 'problem' : 'problems'} today`
}

function Quests({ rewards, onOpen }: { rewards?: RewardsProgress; onOpen: () => void }) {
  const daily = rewards?.quests.find(task => task.kind === 'DAILY_QUEST')
  const weekly = rewards?.quests.find(task => task.kind === 'WEEKLY_QUEST')
  const tasks = [daily, rewards?.featured.task, weekly].filter((task): task is Task => Boolean(task))
  const finished = tasks.filter(task => task.completed >= task.target).length
  return <section className="deck-panel quests-panel" aria-labelledby="overview-quests">
    <header className="deck-header">
      <div className="deck-cluster"><h2 id="overview-quests">Practice quests</h2>
        {tasks.length > 0 && <span className="deck-chip">{finished}/{tasks.length} completed</span>}</div>
      <Action onClick={onOpen}>View quests <Icon name="arrow_forward" /></Action>
    </header>
    {tasks.length > 0 ? <>
      <progress className="deck-progress" value={finished} max={tasks.length} aria-label="Quests completed" />
      <ul className="deck-list">{tasks.map(task => <li className="quest-row" key={task.kind}>
        <span className={`quest-check ${task.completed >= task.target ? 'complete' : ''}`} aria-hidden="true">
          {task.completed >= task.target ? <Icon name="check" /> : <span />}
        </span>
        <div><strong>{questLabel(task)}</strong><small>{task.completed} of {task.target} completed</small></div>
        <span className="xp-accent">+{task.rewardXp} XP</span>
      </li>)}</ul>
    </> : <EmptyFeed icon="task_alt" text="Quest progress will appear here." />}
  </section>
}

function Leaderboard({ standings, accountId, onOpen }: { standings?: Standings; accountId: string; onOpen: () => void }) {
  const top = standings?.items.slice(0, 4) ?? []
  const maximum = Math.max(1, ...top.map(row => row.xp))
  return <section className="deck-panel" aria-labelledby="overview-leaderboard">
    <header className="deck-header">
      <div><h2 id="overview-leaderboard" className="deck-cluster">Weekly leaderboard <span className="signal-dot signal-blue" aria-hidden="true" /></h2>
        {standings && <p className="deck-muted-code">XP earned during group membership</p>}</div>
      <Action onClick={onOpen}>{standings ? 'View all' : 'Find a group'} <Icon name="arrow_forward" /></Action>
    </header>
    {top.length ? <ol className="deck-list">{top.map((row, index) => <li className={`leader-row ${row.id === accountId ? 'leader-self' : ''}`} key={row.id}>
      <span className="leader-rank">{String(row.rank).padStart(2, '0')}</span>
      <Avatar name={row.displayName} className={`tone-${index % 3}`} />
      <div className="leader-name"><strong>{row.displayName}</strong>
        {row.id === accountId && <span className="deck-chip">You</span>}<small>@{row.handle}</small></div>
      <div className="leader-score"><span>{number.format(row.xp)} XP</span>
        <span className="leader-meter" aria-hidden="true"><span style={{ width: `${row.xp / maximum * 100}%` }} /></span></div>
    </li>)}</ol> : <EmptyFeed icon="trophy" text="Join a group to see this week’s standings." />}
  </section>
}

function CodingActivity({ rewards, snapshot, onAnalytics }: {
  rewards?: RewardsProgress; snapshot: LeetCodePreview | null; onAnalytics: () => void
}) {
  const [selectedDate, setSelectedDate] = useState<string | null>(null)
  const end = new Date((rewards?.activity.calendarEnd ?? new Date().toISOString().slice(0, 10)) + 'T00:00:00Z')
  const dates = Array.from({ length: 7 }, (_, index) => {
    const day = new Date(end)
    day.setUTCDate(end.getUTCDate() - 6 + index)
    const date = day.toISOString().slice(0, 10)
    const problems = new Map<string, { title: string; source: string }>()
    for (const item of rewards?.recentSolves ?? []) {
      if (item.date === date) problems.set(item.slug, {
        title: item.title, source: 'LeetGrind',
      })
    }
    for (const item of snapshot?.recentSubmissions ?? []) {
      const submitted = new Date(item.timestamp * 1000)
      if (item.titleSlug && submitted.toISOString().slice(0, 10) === date && !problems.has(item.titleSlug)) {
        problems.set(item.titleSlug, { title: item.title, source: 'Public LeetCode' })
      }
    }
    return { day, date, problems }
  })
  const active = dates.filter(day => day.problems.size > 0).length
  const selected = dates.find(day => day.date === selectedDate) ?? dates[dates.length - 1]
  return <section className="deck-panel coding-panel" aria-labelledby="overview-coding">
    <header className="deck-header"><div><h2 id="overview-coding">Your coding activity</h2>
      <p className="deck-muted-code">Last 7 days · UTC</p></div>
      <Action onClick={onAnalytics}>Analytics <Icon name="arrow_forward" /></Action>
    </header>
    <ul className="orbit-chart" aria-label="Coding days">
      {dates.map(({ day, date, problems }, index) => <li key={date}>
        <button type="button" data-active={problems.size > 0} data-today={index === 6} aria-pressed={date === selected.date}
          aria-label={`${fullDate.format(day)}, ${problems.size} ${problems.size === 1 ? 'problem' : 'problems'} solved`}
          onClick={() => setSelectedDate(date)}>
          <span className="orbit-track" aria-hidden="true"><span /></span><span aria-hidden="true">{dayLabel.format(day)}</span>
        </button>
      </li>)}
    </ul>
    <div className="chart-caption"><span>Active days</span><strong>{active} / 7</strong></div>
    <div className="coding-day" aria-live="polite">
      <h3>{fullDate.format(selected.day)}</h3>
      {selected.problems.size > 0 ? <ul className="deck-list">{Array.from(selected.problems, ([slug, problem]) =>
        <li key={slug}><span>{problem.title}</span><small>{problem.source}</small></li>)}</ul>
        : <p className="muted">{rewards ? 'No solves recorded for this day.' : 'Loading solves…'}</p>}
    </div>
  </section>
}

export function Dashboard({ profile, group, onLeaderboard, onPractice, onProfile, onQuests, onAnalytics }: {
  profile: Profile
  group?: Group
  onLeaderboard: () => void
  onPractice: () => void
  onProfile: () => void
  onQuests: () => void
  onAnalytics: () => void
}) {
  const { snapshot, refresh, error: profileError } = usePublicLeetCode()
  const [refreshingProfile, setRefreshingProfile] = useState(false)
  const [personal, setPersonal] = useState<DashboardData | null>(null)
  const [groupData, setGroupData] = useState<GroupData | null>(null)
  const [error, setError] = useState('')
  const [pending, setPending] = useState(true)
  const groupId = group?.id
  useEffect(() => {
    let keep = true
    let fetching = false
    async function load() {
      if (fetching) return
      fetching = true
      const groupRequest = groupId ? Promise.all([
        groupsApi.standings(profile.id, groupId, 'live', 'weekly', ''),
        rewardsApi.groupFeatured(profile.id, groupId, 'live'),
      ]) : Promise.resolve(null)
      const [self, team] = await Promise.allSettled([Promise.all([
        rewardsApi.progress(profile.id, 'live'), practiceApi.history(profile.id), practiceApi.status(profile.id),
      ]), groupRequest])
      if (keep) {
        if (self.status === 'fulfilled') {
          const [rewards, history, link] = self.value
          setPersonal({ rewards, history: history.items, link })
        }
        if (team.status === 'fulfilled' && team.value) {
          const [standings, featured] = team.value
          setGroupData({ standings, featured })
        } else {
          setGroupData(null)
        }
        const failure = self.status === 'rejected' ? self.reason : team.status === 'rejected' ? team.reason : null
        setError(failure instanceof ApiError ? failure.message : failure ? 'Dashboard data could not be loaded.' : '')
        setPending(false)
      }
      fetching = false
    }
    void load()
    function refreshVisible() {
      if (document.visibilityState === 'visible') void load()
    }
    const interval = window.setInterval(refreshVisible, 8_000)
    document.addEventListener('visibilitychange', refreshVisible)
    return () => {
      keep = false
      window.clearInterval(interval)
      document.removeEventListener('visibilitychange', refreshVisible)
    }
  }, [profile.id, groupId])
  const rewards = personal?.rewards
  const rank = groupData?.standings.items.find(row => row.id === profile.id)?.rank
  return <div className="orbital-dashboard" aria-busy={pending}>
    <section className="deck-panel dashboard-hero" aria-labelledby="welcome-heading">
      <div>
        <p className="deck-kicker">{fullDate.format(new Date())} <span>· UTC</span></p>
        <h1 id="welcome-heading">Welcome back, {profile.displayName}.</h1>
        <p>{group ? `Your practice and progress with ${group.name}.` : 'Track your practice and build your next streak.'}</p>
      </div>
      <SyncStatus link={personal?.link.fixture ? undefined : personal?.link} onProfile={onProfile} />
    </section>
    {error && <p className="notice error" role="alert">{error}</p>}
    <Activity profile={profile} history={personal?.history ?? []} onPractice={onPractice} />

    <div className="deck-metrics">
      <Metric title="Practice XP" icon="bolt" value={rewards ? number.format(rewards.totalXp) : '—'} unit="Total XP">
        {rewards ? <><span className="metric-highlight">Level {rewards.level.number}</span>
          {rewards.level.nextXp !== null && <> · {number.format(rewards.level.nextXp - rewards.totalXp)} XP to next level</>}</> : 'Loading rewards'}
      </Metric>
      <Metric title="Orbit cycle" icon="local_fire_department" value={rewards ? String(rewards.loginStreak.currentStreak) : '—'} unit="Sign-in day streak" tone="blue">
        <span className="streak-lights" aria-hidden="true">{Array.from({ length: 7 }, (_, index) =>
          <span key={index} data-active={index < (rewards?.loginStreak.currentStreak ?? 0)} />)}</span>
        {rewards ? `Best streak ${rewards.loginStreak.longestStreak}` : 'Loading activity'}
      </Metric>
      <Metric title="Group rank" icon="trophy" value={rank ? `#${rank}` : '—'} unit="Weekly standing">
        <span className="metric-highlight">{group ? group.name : 'Join a group to rank'}</span>
      </Metric>
    </div>

    {personal?.link.handle && !personal.link.fixture && personal.link.state !== 'DISCONNECTED' && <section className="deck-panel" aria-labelledby="overview-leetcode">
      <header className="deck-header"><h2 id="overview-leetcode">Public LeetCode progress</h2>
        {personal.link.previewEnabled && <button type="button" className="deck-action" disabled={refreshingProfile} onClick={async () => {
          setRefreshingProfile(true)
          await refresh(personal.link)
          setRefreshingProfile(false)
        }}>{refreshingProfile ? 'Refreshing…' : 'Refresh public activity'}</button>}
      </header>
      {profileError && <p role="alert">{profileError}</p>}
      {snapshot && <PublicLeetCodeStats snapshot={snapshot} />}
    </section>}

    <div className="deck-grid">
      <div className="deck-column">
        <Featured feature={rewards?.featured} team={groupData?.featured} onPractice={onPractice} />
        <Quests rewards={rewards} onOpen={onQuests} />
      </div>
      <div className="deck-column">
        <Leaderboard accountId={profile.id} standings={groupData?.standings} onOpen={onLeaderboard} />
        <CodingActivity rewards={rewards} snapshot={snapshot} onAnalytics={onAnalytics} />
      </div>
    </div>
  </div>
}
