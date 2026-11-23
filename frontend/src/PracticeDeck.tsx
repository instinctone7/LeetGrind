import { useEffect, useRef, useState } from 'react'
import type { CatalogProblem, Difficulty } from './api'
import { Icon } from './Icons'

const difficulties = ['EASY', 'MEDIUM', 'HARD'] as const
const difficultyOrder = { EASY: 0, MEDIUM: 1, HARD: 2 }
const tracks = [
  { title: 'Trees & graphs', description: 'Tree traversal and graph search', topics: ['Tree', 'Graph', 'Depth-First Search', 'Breadth-First Search'], icon: 'code' },
  { title: 'Arrays & hashing', description: 'Lookups, windows, and array patterns', topics: ['Array', 'Hash Table'], icon: 'dashboard' },
  { title: 'Dynamic programming', description: 'Practice breaking problems into subproblems', topics: ['Dynamic Programming'], icon: 'analytics' },
]
const pageSize = 8
const sprintSeconds = 15 * 60
const count = new Intl.NumberFormat()

type Sort = 'title' | 'difficulty' | 'unsolved'
type Status = 'all' | 'solved' | 'unrecorded'
type Encounter = { problem: CatalogProblem; endsAt?: number }

function difficultyName(difficulty: Difficulty) {
  return difficulty[0] + difficulty.slice(1).toLowerCase()
}

function problemUrl(slug: string) {
  return 'https://leetcode.com/problems/' + encodeURIComponent(slug) + '/'
}

function sortProblems(left: CatalogProblem, right: CatalogProblem, sort: Sort) {
  if (sort === 'difficulty') return difficultyOrder[left.difficulty] - difficultyOrder[right.difficulty] || left.title.localeCompare(right.title)
  if (sort === 'unsolved') return Number(left.solved) - Number(right.solved) || left.title.localeCompare(right.title)
  return left.title.localeCompare(right.title)
}

export function PracticeDeck({ problems, loading, catalogError, onRetry, solved, publicProfile, level, streak, quota }: {
  problems: CatalogProblem[]
  loading: boolean
  catalogError: string
  onRetry: () => void
  solved: number
  publicProfile: boolean
  level?: number
  streak?: number
  quota?: { completed: number; target: number; endsAt: string }
}) {
  const [query, setQuery] = useState('')
  const [topics, setTopics] = useState<string[]>([])
  const [difficulty, setDifficulty] = useState<Difficulty | ''>('')
  const [status, setStatus] = useState<Status>('all')
  const [sort, setSort] = useState<Sort>('title')
  const [page, setPage] = useState(0)
  const [encounter, setEncounter] = useState<Encounter | null>(null)
  const [seconds, setSeconds] = useState(sprintSeconds)
  const search = useRef<HTMLInputElement>(null)

  const searchTerm = query.trim().toLowerCase()
  const topicNames = Array.from(new Set(problems.flatMap(problem => problem.topics))).sort()
  const matches = problems.filter(problem =>
    (!searchTerm || [problem.title, problem.slug, ...problem.topics].some(text => text.toLowerCase().includes(searchTerm)))
    && (!topics.length || problem.topics.some(topic => topics.includes(topic)))
    && (!difficulty || difficulty === problem.difficulty)
    && (status === 'all' || problem.solved === (status === 'solved')))
    .sort((left, right) => sortProblems(left, right, sort))
  const lastPage = Math.max(0, Math.ceil(matches.length / pageSize) - 1)
  const currentPage = Math.min(page, lastPage)
  const candidates = matches.filter(problem => !problem.solved)
  const total = quota ? Math.min(quota.completed, quota.target) : 0
  const quotaPercent = quota?.target ? Math.round(total / quota.target * 100) : 0
  const radar = topicNames.map(topic => {
    const items = problems.filter(problem => problem.topics.includes(topic))
    return { topic, available: items.length, solved: items.filter(problem => problem.solved).length }
  }).sort((left, right) => right.available - left.available || left.topic.localeCompare(right.topic)).slice(0, 5)

  const endsAt = encounter?.endsAt
  useEffect(() => {
    if (endsAt === undefined) return
    const interval = window.setInterval(() => {
      const remaining = Math.max(0, Math.ceil((endsAt - Date.now()) / 1000))
      setSeconds(remaining)
      if (remaining === 0) window.clearInterval(interval)
    }, 250)
    return () => window.clearInterval(interval)
  }, [endsAt])

  function launch(timed: boolean) {
    const available = candidates.filter(problem => problem.slug !== encounter?.problem.slug)
    const choices = available.length ? available : candidates
    const problem = choices[Math.floor(Math.random() * choices.length)]
    if (!problem) return
    setSeconds(sprintSeconds)
    setEncounter({ problem, endsAt: timed ? Date.now() + sprintSeconds * 1000 : undefined })
  }

  function filterTopics(next: string[]) {
    setTopics(next)
    setPage(0)
  }

  function openTrack(next: string[]) {
    setQuery('')
    setDifficulty('')
    setStatus('all')
    filterTopics(next)
    search.current?.focus()
  }

  return <>
    <header className="practice-hero">
      <div>
        <h1>Practice &amp; Mastery</h1>
        <p className="muted">Find your next problem, track topic coverage, or start a timed sprint.</p>
      </div>
      <div className="practice-launch">
        <button type="button" className="text-button" disabled={!candidates.length || Boolean(endsAt && seconds)}
          onClick={() => launch(false)}><Icon name="bolt" /> Random encounter</button>
        <button type="button" className="deck-primary" disabled={!candidates.length || Boolean(endsAt && seconds)}
          onClick={() => launch(true)}><Icon name="event" /> Start timed sprint</button>
      </div>
    </header>

    {encounter && <section className="practice-card practice-encounter" aria-label="Practice encounter">
      <div>
        <p className="deck-kicker" role="status">{endsAt ? seconds ? 'Timed sprint' : 'Sprint complete' : 'Random encounter'}</p>
        <a href={problemUrl(encounter.problem.slug)} target="_blank" rel="noreferrer">{encounter.problem.title} <Icon name="open_in_new" /></a>
      </div>
      {endsAt && <strong className="practice-timer" role="timer" aria-label="Time remaining">{Math.floor(seconds / 60)}:{String(seconds % 60).padStart(2, '0')}</strong>}
      <button type="button" className="text-button" onClick={() => setEncounter(null)}>{endsAt && seconds ? 'End sprint' : 'Dismiss'}</button>
    </section>}

    <dl className="practice-telemetry practice-card">
      <div><dt>{publicProfile ? 'LeetCode solves' : 'Recorded solves'}</dt><dd>{count.format(solved)}</dd></div>
      <div><dt>Mastery level</dt><dd>{level === undefined ? '—' : 'Level ' + count.format(level)}</dd></div>
      <div><dt>Weekly goal</dt><dd>{quota ? total + ' / ' + quota.target : '—'}</dd></div>
      <div><dt>{publicProfile ? 'LeetCode streak' : 'Active streak'}</dt><dd>{streak === undefined ? '—' : count.format(streak) + (streak === 1 ? ' day' : ' days')}</dd></div>
    </dl>

    {problems.length > 0 && <div className="practice-tracks">
      {tracks.map(track => {
        const items = problems.filter(problem => problem.topics.some(topic => track.topics.includes(topic)))
        if (!items.length) return null
        const completed = items.filter(problem => problem.solved).length
        const next = items.find(problem => !problem.solved)
        return <section className="practice-card practice-track" key={track.title}>
          <Icon name={track.icon} />
          <h2>{track.title}</h2>
          <p className="muted">{track.description}</p>
          <progress value={completed} max={items.length} aria-label={track.title + ' progress'} />
          <p className="deck-kicker">{completed} / {items.length} solved</p>
          <div className="practice-track-footer">
            <small>{next ? 'Next: ' + next.title : 'Track complete'}</small>
            <button type="button" className="text-button" aria-label={'View ' + track.title + ' problems'}
              onClick={() => openTrack(track.topics)}><Icon name="arrow_forward" /></button>
          </div>
        </section>
      })}
    </div>}

    <div className="practice-workstation">
      <section className="practice-problems" aria-labelledby="practice-problems-heading">
        <div className="practice-card practice-filters">
          <h2 id="practice-problems-heading">Problem catalog</h2>
          <div className="practice-search">
            <label><span className="visually-hidden">Filter problems</span>
              <input ref={search} value={query} placeholder="Search problems or topics…" onChange={event => {
                setQuery(event.target.value)
                setPage(0)
              }} />
            </label>
            <label><span className="visually-hidden">Sort problems</span>
              <select value={sort} onChange={event => { setSort(event.target.value as Sort); setPage(0) }}>
                <option value="title">Sort: Title</option><option value="difficulty">Sort: Difficulty</option>
                <option value="unsolved">Sort: Unsolved first</option>
              </select>
            </label>
          </div>
          {topicNames.length > 0 && <div className="practice-topics" role="group" aria-label="Topic filters">
            <button type="button" aria-pressed={!topics.length} onClick={() => filterTopics([])}>All topics</button>
            {topicNames.map(topic => <button type="button" key={topic} aria-pressed={topics.includes(topic)}
              onClick={() => filterTopics(topics.length === 1 && topics[0] === topic ? [] : [topic])}>{topic}</button>)}
          </div>}
          <div className="practice-filter-row">
            <label>Difficulty<select value={difficulty} onChange={event => {
              setDifficulty(event.target.value as Difficulty | '')
              setPage(0)
            }}><option value="">All</option>{difficulties.map(item => <option key={item} value={item}>{difficultyName(item)}</option>)}</select></label>
            <label>Problem status<select value={status} onChange={event => {
              setStatus(event.target.value as Status)
              setPage(0)
            }}><option value="all">All</option><option value="unrecorded">Unsolved</option><option value="solved">Solved</option></select></label>
          </div>
        </div>
        <div className="practice-card practice-results">
          {loading && <p role="status">Loading problems…</p>}
          {catalogError && <div role="alert"><p>{catalogError}</p><button className="text-button" type="button" onClick={onRetry}>Reload catalog</button></div>}
          {!loading && !catalogError && !matches.length && <p>No problems match these filters.</p>}
          {matches.length > 0 && <>
            <div className="practice-table-scroll">
              <table className="practice-table">
                <caption className="visually-hidden">LeetCode practice problems</caption>
                <thead><tr><th scope="col">Status</th><th scope="col">Problem &amp; topics</th><th scope="col">Difficulty</th><th scope="col">Action</th></tr></thead>
                <tbody>{matches.slice(currentPage * pageSize, (currentPage + 1) * pageSize).map(problem => <tr key={problem.slug}>
                  <td><span className={problem.solved ? 'practice-solved' : 'practice-unsolved'}>
                    {problem.solved ? <Icon name="check" /> : <span aria-hidden="true" className="practice-status-dot" />}
                    <span className="visually-hidden">{problem.solved ? 'Solved' : 'No known solve'}</span></span></td>
                  <th scope="row"><strong>{problem.title}</strong><small>{problem.topics.join(' · ')}</small></th>
                  <td><span className="practice-tier" data-tier={problem.difficulty}>{difficultyName(problem.difficulty)}</span></td>
                  <td><a className="text-button" href={problemUrl(problem.slug)} target="_blank" rel="noreferrer"
                    aria-label={(problem.solved ? 'Review ' : 'Solve ') + problem.title}>{problem.solved ? 'Review' : 'Solve'} <Icon name="open_in_new" /></a></td>
                </tr>)}</tbody>
              </table>
            </div>
            <div className="practice-pagination">
              <span role="status">{currentPage * pageSize + 1}–{Math.min(matches.length, (currentPage + 1) * pageSize)} of {matches.length} problems</span>
              {lastPage > 0 && <div>
                <button type="button" className="text-button" disabled={!currentPage} onClick={() => setPage(currentPage - 1)}>Previous</button>
                <button type="button" className="text-button" disabled={currentPage === lastPage} onClick={() => setPage(currentPage + 1)}>Next</button>
              </div>}
            </div>
          </>}
          {publicProfile && <p className="practice-status-note muted">Solve status includes recent public acceptances and LeetGrind records.</p>}
        </div>
      </section>

      <aside className="practice-rail" aria-label="Practice progress">
        <section className="practice-card practice-radar">
          <h2>Algorithmic radar</h2>
          <p className="deck-kicker">Catalog coverage</p>
          {radar.length > 0 ? <ul>{radar.map(item => <li key={item.topic}>
            <div><span>{item.topic}</span><span>{item.solved} / {item.available}</span></div>
            <progress value={item.solved} max={item.available} aria-label={item.topic + ' coverage'} />
          </li>)}</ul> : <p className="muted">Topic coverage appears when the catalog loads.</p>}
        </section>
        <section className="practice-card practice-quota">
          <h2>Weekly practice quota</h2>
          {quota ? <div className="practice-quota-body">
            <div><strong>{total} / {quota.target}</strong><p className="muted">New credited solves</p>
              <small>Resets {new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' }).format(new Date(quota.endsAt))} UTC</small></div>
            <div className="practice-quota-ring" style={{ background: `conic-gradient(var(--accent) ${quotaPercent}%, var(--border) 0)` }}><span>{quotaPercent}%</span></div>
          </div> : <p className="muted">Weekly progress loads with your rewards.</p>}
        </section>
      </aside>
    </div>
  </>
}
