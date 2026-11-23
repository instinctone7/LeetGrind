import { useEffect, useState } from 'react'
import { ApiError, competitionApi, practiceApi } from './api'
import type { Challenge, ChallengeView, DuelDraft, DuelSummary, Group, GroupMember, Notification } from './api'
import { DuelArena, DuelClock } from './DuelArena'

const dateTime = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short', timeZone: 'UTC' })
const statuses: Record<Challenge['status'], string> = {
  SCHEDULED: 'Scheduled', AWAITING_OPPONENT: 'Awaiting opponent', ACTIVE: 'Active',
  GRACE: 'Awaiting late syncs and finalization', FINALIZED: 'Finalized',
}
const outcomes: Record<NonNullable<Challenge['outcome']>, string> = {
  WIN: 'Winner recorded', TIE: 'Shared first place', NO_SCORE: 'No qualifying solves', CANCELLED: 'Cancelled',
}

function timestamp(at: string) {
  return dateTime.format(new Date(at)) + ' UTC'
}

function message(error: unknown) {
  return error instanceof ApiError ? error.message : 'LeetGrind could not be reached. Try again.'
}

function ruleText(challenge: Challenge) {
  if (challenge.rule === 'PROBLEM') return 'First solve of ' + challenge.problemSlug
  const difficulty = challenge.difficulty ? challenge.difficulty.toLowerCase() + ' ' : ''
  return `Solve ${challenge.target} distinct new ${difficulty}problem${challenge.target === 1 ? '' : 's'}`
}

function ChallengeDetails({ selected, busy, onEnroll, onClose, onSync }: {
  selected: ChallengeView
  busy: boolean
  onEnroll: () => void
  onClose: () => void
  onSync: () => void
}) {
  const { challenge, participants } = selected
  return <section className="competition-result" aria-labelledby="challenge-heading">
    <button type="button" className="text-button" onClick={onClose}>Back to competitions</button>
    <h3 id="challenge-heading">{challenge.title}</h3>
    <p>Duel · {statuses[challenge.status]}
      {challenge.outcome && ' · ' + outcomes[challenge.outcome]}</p>
    {challenge.problemUrl ? <>
      <p><a href={challenge.problemUrl} target="_blank" rel="noreferrer">Open {challenge.title} on LeetCode</a></p>
      <p>{challenge.durationMinutes} minutes · {challenge.stakeXp} XP per player</p>
      {challenge.status === 'AWAITING_OPPONENT' && <p>First accepted submission wins. If neither player solves before time expires, both lose their stake.</p>}
      {(challenge.status === 'ACTIVE' || challenge.status === 'GRACE') && <DuelClock deadline={challenge.endsAt} />}
      {challenge.status === 'ACTIVE' && <button type="button" className="text-button" disabled={busy} onClick={onSync}>Check my solve</button>}
    </> : <><p>{ruleText(challenge)}. The highest score up to {challenge.target} shares first place if tied.</p>
    <dl className="competition-dates">
      <div><dt>Starts</dt><dd>{timestamp(challenge.startsAt)}</dd></div>
      <div><dt>Deadline</dt><dd>{timestamp(challenge.endsAt)}</dd></div>
      <div><dt>Sync cutoff</dt><dd>{timestamp(challenge.cutoffAt)}</dd></div>
    </dl></>}
    <ul className="group-list">{participants.map(participant => <li key={participant.id}>
      <span><strong>{participant.displayName}</strong><small>@{participant.handle}</small></span>
      <span>{participant.enrolledAt ? `${participant.score} of ${challenge.target}` : 'Invited'}
        {participant.winner && <small>{challenge.outcome === 'TIE' ? 'Tied for first' : 'Winner'}</small>}
        {participant.xpDelta != null && <small>{participant.xpDelta > 0 ? '+' : ''}{participant.xpDelta} XP</small>}</span>
    </li>)}</ul>
    {selected.canEnroll && <button type="button" className="text-button" disabled={busy} onClick={onEnroll}>
      Accept duel
    </button>}
  </section>
}

type GroupDetails = { group: Group; members: GroupMember[] }

export function Competition({ accountId, detail, initialChallengeId, expanded = false }: {
  accountId: string
  detail: GroupDetails
  initialChallengeId: string | null
  expanded?: boolean
}) {
  const group = detail.group.id
  const [opened, setOpened] = useState(expanded || initialChallengeId !== null)
  const [challenges, setChallenges] = useState<Challenge[] | null>(null)
  const [duelSummary, setDuelSummary] = useState<DuelSummary | null>(null)
  const [selectedId, setSelectedId] = useState(initialChallengeId)
  const [selected, setSelected] = useState<ChallengeView | null>(null)
  const [browsingOlder, setBrowsingOlder] = useState(false)
  const [more, setMore] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    if (!opened) return
    let keep = true
    let waiting = false
    async function load() {
      if (waiting) return
      waiting = true
      try {
        const [next, current, summary] = await Promise.all([
          opened && !browsingOlder ? competitionApi.list(accountId, group, 'live') : null,
          opened && selectedId ? competitionApi.detail(accountId, group, selectedId) : null,
          opened ? competitionApi.duelSummary(accountId, group) : null,
        ])
        if (!keep) return
        if (next) {
          setChallenges(next)
          setMore(next.length === 30)
        }
        if (current) setSelected(current)
        if (summary) setDuelSummary(summary)
      } catch (failure) {
        if (keep) {
          setError(message(failure))
          if (failure instanceof ApiError && failure.status === 404) {
            setSelected(null)
            setChallenges(null)
          }
        }
      } finally {
        waiting = false
      }
    }
    void load()
    const interval = window.setInterval(() => { if (document.visibilityState === 'visible') void load() }, 8_000)
    return () => {
      keep = false
      window.clearInterval(interval)
    }
  }, [accountId, group, opened, browsingOlder, selectedId, revision])

  function openChallenge(id: string | null) {
    setSelected(null)
    setSelectedId(id)
    setError('')
    if (id) setOpened(true)
  }

  async function submit(draft?: DuelDraft): Promise<boolean> {
    setBusy(true)
    setError('')
    try {
      const entry = draft ? await competitionApi.createDuel(accountId, group, draft)
        : await competitionApi.enroll(accountId, group, selectedId!)
      setSelected(entry)
      setSelectedId(entry.challenge.id)
      setBrowsingOlder(false)
      setRevision(value => value + 1)
      return true
    } catch (failure) {
      if (failure instanceof ApiError && failure.status === 404) setSelected(null)
      setError(message(failure))
      return false
    } finally {
      setBusy(false)
    }
  }

  async function checkMySolve() {
    setBusy(true)
    setError('')
    try {
      await practiceApi.sync(accountId)
      setRevision(value => value + 1)
    } catch (failure) {
      setError(message(failure))
    } finally {
      setBusy(false)
    }
  }

  async function older() {
    const before = challenges?.at(-1)?.id
    if (!before) return
    setBusy(true)
    try {
      const entries = await competitionApi.list(accountId, group, 'live', before)
      setBrowsingOlder(true)
      setChallenges(previous => [...(previous ?? []), ...entries])
      setMore(entries.length === 30)
    } catch (failure) {
      setError(message(failure))
    } finally {
      setBusy(false)
    }
  }

  return <section className="group-tools" aria-label="Group duels">
    {error && <p className="notice error" role="alert">{error}</p>}
    <details open={opened} onToggle={event => setOpened(event.currentTarget.open)}>
      <summary>Duels</summary>
      {opened && (selectedId ? selected
        ? <ChallengeDetails selected={selected} busy={busy} onEnroll={() => void submit()} onClose={() => openChallenge(null)}
          onSync={() => void checkMySolve()} />
        : error ? <button type="button" className="text-button" onClick={() => openChallenge(null)}>Back to competitions</button>
          : <p role="status">Loading competition…</p>
        : <>
          <DuelArena accountId={accountId} groupName={detail.group.name} members={detail.members}
            entries={challenges?.filter(entry => entry.kind === 'DUEL') ?? []} summary={duelSummary} busy={busy}
            onOpen={openChallenge} onCreate={submit} />
          {more && <button type="button" className="text-button" disabled={busy} onClick={() => void older()}>Older competitions</button>}
        </>)}
    </details>
  </section>
}

function noticeText(notice: Notification) {
  switch (notice.kind) {
    case 'INVITATION': return 'Invitation to ' + notice.challengeTitle
    case 'CHALLENGE_RESULT': return 'Result for ' + notice.challengeTitle
    case 'ACHIEVEMENT': return 'Achievement unlocked · ' + notice.label
    case 'STANDINGS_CHANGE': return notice.label + ' · ' + notice.groupName
  }
}

export function Inbox({ accountId, onOpen }: { accountId: string; onOpen: (group: string, challenge: string | null) => void }) {
  const [items, setItems] = useState<Notification[] | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [more, setMore] = useState(false)
  const [showOlder, setShowOlder] = useState(false)

  useEffect(() => {
    if (showOlder) return
    let keep = true
    function load() {
      competitionApi.inbox(accountId).then(result => {
        if (keep) {
          setItems(result)
          setMore(result.length === 50)
        }
      }).catch(failure => { if (keep) setError(message(failure)) })
    }
    load()
    const timer = window.setInterval(() => { if (document.visibilityState === 'visible') load() }, 8_000)
    return () => {
      keep = false
      window.clearInterval(timer)
    }
  }, [accountId, showOlder])

  async function act(notice?: Notification) {
    setBusy(true)
    setError('')
    try {
      if (notice) {
        await competitionApi.read(accountId, notice.id)
        if (notice.groupId) onOpen(notice.groupId, notice.challengeId)
        else setItems(previous => previous?.map(item => item.id === notice.id
          ? { ...item, readAt: item.readAt ?? new Date().toISOString() } : item) ?? null)
      } else {
        const next = await competitionApi.inbox(accountId, items?.at(-1)?.id)
        setShowOlder(true)
        setItems(previous => [...(previous ?? []), ...next])
        setMore(next.length === 50)
      }
    } catch (failure) {
      if (notice && failure instanceof ApiError && failure.status === 404) {
        setItems(previous => previous?.filter(item => item.id !== notice.id) ?? null)
      }
      setError(message(failure))
    } finally {
      setBusy(false)
    }
  }

  return <>
    <h1>Notifications</h1>
    {error && <p className="notice error" role="alert">{error}</p>}
    {!items ? !error && <p role="status">Loading notifications…</p> : items.length
      ? <ul className="group-list">{items.map(notice => <li key={notice.id}>
          <span><strong>{noticeText(notice)}</strong><small>{timestamp(notice.createdAt)}{!notice.readAt && ' · Unread'}</small></span>
          {(notice.groupId || !notice.readAt) && <button type="button" className="text-button" disabled={busy}
            aria-label={(notice.groupId ? 'Open ' : 'Mark read ') + noticeText(notice)} onClick={() => void act(notice)}>
            {notice.groupId ? 'Open' : 'Mark read'}
          </button>}
        </li>)}</ul>
      : <p className="muted">You have no notifications.</p>}
    {more && <button type="button" className="text-button" disabled={busy} onClick={() => void act()}>Older notifications</button>}
  </>
}
