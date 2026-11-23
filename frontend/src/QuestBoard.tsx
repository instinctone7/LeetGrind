import { useEffect, useState } from 'react'
import type { GroupFeatured, RewardsProgress } from './api'
import { Icon } from './Icons'
import './quests.css'

type Task = RewardsProgress['quests'][number]
const utc = new Intl.DateTimeFormat(undefined, {
  month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'UTC',
})

function Deadline({ at }: { at: string }) {
  return <time dateTime={at}>{utc.format(new Date(at))} UTC</time>
}

export function RewardCountdown({ endsAt, className }: { endsAt: string; className?: string }) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [])
  const seconds = Math.max(0, Math.floor((Date.parse(endsAt) - now) / 1000))
  const value = [Math.floor(seconds / 3600), Math.floor(seconds / 60) % 60, seconds % 60]
    .map(part => String(part).padStart(2, '0')).join(':')
  return <span className={className}>Resets in {value}</span>
}

function QuestStatus({ task }: { task: Task }) {
  return <span>{task.completed >= task.target ? 'Completed' : `${task.completed} of ${task.target} solved`}</span>
}

function DailyHighlight({ featured, group }: { featured: RewardsProgress['featured']; group: GroupFeatured | null }) {
  const { task } = featured
  const completed = task.completed >= task.target
  const groupProgress = group?.startsAt === task.startsAt ? group : null
  return <section className="quest-highlight quest-panel" aria-labelledby="daily-highlight-heading">
    <header className="quest-highlight-header">
      <h2 id="daily-highlight-heading"><span aria-hidden="true" />Daily highlight</h2>
      <strong className="quest-xp">+{task.rewardXp} XP</strong>
    </header>
    <h3>{featured.title}</h3>
    <p>Earn bonus XP for your first accepted solve in today’s practice window.</p>
    <div className="quest-topics">
      <span className={`quest-difficulty difficulty-${featured.difficulty.toLowerCase()}`}>{featured.difficulty.toLowerCase()}</span>
      {featured.topics.map(topic => <span key={topic}>{topic}</span>)}
    </div>
    <footer className="quest-highlight-footer">
      <div>
        <div className="quest-highlight-status">
          <QuestStatus task={task} />
          {groupProgress && <span>{groupProgress.completed} of {groupProgress.members} group members solved</span>}
        </div>
        <small>Sync by <Deadline at={task.cutoffAt} /></small>
      </div>
      {featured.url && <a className="quest-action" href={featured.url} target="_blank" rel="noreferrer">
        {completed ? 'Review on LeetCode' : 'Solve on LeetCode'} <Icon name="arrow_forward" />
      </a>}
    </footer>
  </section>
}

function Directives({ tasks, onPractice }: { tasks: Task[]; onPractice: () => void }) {
  return <section className="quest-directives" aria-labelledby="directives-heading">
    <h2 id="directives-heading">Directives</h2>
    <ul className="quest-panel quest-directive-list">
      {tasks.map(task => {
        const daily = task.kind === 'DAILY_QUEST'
        const title = task.topic
          ? `Solve ${task.target} new ${task.topic.toLowerCase()} ${task.target === 1 ? 'problem' : 'problems'}`
          : `Solve ${task.target} distinct new problems`
        return <li key={task.kind} className="quest-directive">
          <div className="quest-directive-title">
            <span className="quest-period">{daily ? 'Daily' : 'Weekly'}</span><h3>{title}</h3>
          </div>
          <strong className="quest-xp">+{task.rewardXp} XP</strong>
          <p className="quest-directive-status"><QuestStatus task={task} /> <span>· Closes <Deadline at={task.endsAt} /></span></p>
          {task.eligibleProblemSlugs.length > 0
            ? <details className="quest-eligible">
              <summary className="quest-action">Eligible problems</summary>
              <ul>{task.eligibleProblemSlugs.map(slug => <li key={slug}>
                <a href={`https://leetcode.com/problems/${slug}/`} target="_blank" rel="noreferrer">{slug.replaceAll('-', ' ')}</a>
              </li>)}</ul>
            </details>
            : <button type="button" className="quest-action" onClick={onPractice}>View practice <Icon name="arrow_forward" /></button>}
          <small>Sync by <Deadline at={task.cutoffAt} /></small>
        </li>
      })}
    </ul>
  </section>
}

function StreakMilestones({ rewards, onAchievements }: { rewards: RewardsProgress; onAchievements: () => void }) {
  const { currentStreak, longestStreak } = rewards.activity
  const unlocked = rewards.badges.some(badge => badge.code === 'THREE_DAY_STREAK')
  return <section className="quest-streak quest-panel" aria-labelledby="quests-streak-heading">
    <header>
      <h2 id="quests-streak-heading">Streak milestones</h2>
      <span>{currentStreak} {currentStreak === 1 ? 'day' : 'days'}</span>
    </header>
    <p>Best coding streak <strong>{longestStreak} {longestStreak === 1 ? 'day' : 'days'}</strong></p>
    <div className="quest-milestone">
      <div><Icon name={unlocked ? 'check' : 'bolt'} /><h3>Three-day streak</h3>
        {unlocked && <span className="quest-milestone-unlocked">Unlocked</span>}</div>
      {!unlocked && <p>Solve on three consecutive days.</p>}
      <progress aria-label="Three-day coding streak progress" value={unlocked ? 3 : Math.min(3, currentStreak)} max={3} />
    </div>
    <button type="button" className="text-button" onClick={onAchievements}>View achievements <Icon name="arrow_forward" /></button>
  </section>
}

export function QuestBoard({ rewards, groupName, group, error, onPractice, onAchievements }: {
  rewards: RewardsProgress | null
  groupName?: string
  group: GroupFeatured | null
  error: string
  onPractice: () => void
  onAchievements: () => void
}) {
  return <div className="quest-board">
    <header className="quest-hero">
      <div>
        <h1>Quests</h1>
        <p>{groupName ? <>Practice goals for <strong>{groupName}</strong></> : 'Daily and weekly practice goals'}</p>
      </div>
      {rewards && <div className="quest-hero-meta">
        <span className="quest-meta"><Icon name="local_fire_department" />{rewards.activity.currentStreak}-day coding streak</span>
        <span className="quest-meta quest-countdown"><RewardCountdown endsAt={rewards.featured.task.endsAt} /></span>
      </div>}
    </header>
    {error && <p role="alert" className="notice error">{error}</p>}
    {rewards ? <div className="quest-layout">
      <div className="quest-main">
        <DailyHighlight featured={rewards.featured} group={group} />
        <Directives tasks={rewards.quests} onPractice={onPractice} />
      </div>
      <StreakMilestones rewards={rewards} onAchievements={onAchievements} />
    </div> : !error && <p role="status">Loading practice rewards…</p>}
  </div>
}
