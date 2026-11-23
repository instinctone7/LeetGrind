import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, competitionApi } from './api'
import type { Challenge, ChallengeDraft, ChallengeKind, ChallengeRule, ChallengeView, Difficulty, FeedItem, Group, GroupMember, Notification } from './api'

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

function ChallengeForm({ accountId, members, owner, onCreate }: {
  accountId: string
  members: GroupMember[]
  owner: boolean
  onCreate: (draft: ChallengeDraft) => Promise<boolean>
}) {
  const [kind, setKind] = useState<ChallengeKind>(owner ? 'GROUP' : 'DUEL')
  const [rule, setRule] = useState<ChallengeRule>('COUNT')
  const [busy, setBusy] = useState(false)

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const data = new FormData(form)
    const start = String(data.get('startsAt') ?? '')
    const opponent = String(data.get('invitee') ?? '')
    setBusy(true)
    try {
      const draft: ChallengeDraft = {
        title: String(data.get('title') ?? ''), kind, rule, mode: 'live',
        endsAt: new Date(String(data.get('endsAt')) + ':00Z').toISOString(),
      }
      if (start) draft.startsAt = new Date(start + ':00Z').toISOString()
      if (opponent) draft.inviteeId = opponent
      if (rule === 'PROBLEM') draft.problemSlug = String(data.get('slug') ?? '')
      else draft.target = Number(data.get('target'))
      if (rule === 'DIFFICULTY') draft.difficulty = String(data.get('difficulty')) as Difficulty
      if (await onCreate(draft)) form.reset()
    } finally {
      setBusy(false)
    }
  }

  return <details className="competition-panel">
    <summary>Create a challenge or duel</summary>
    <form onSubmit={event => void submit(event)}>
      {owner && <label>Competition type
        <select value={kind} disabled={busy} onChange={event => setKind(event.target.value as ChallengeKind)}>
          <option value="GROUP">Group challenge</option><option value="DUEL">One-on-one duel</option>
        </select>
      </label>}
      <label>Challenge title<input name="title" required maxLength={80} disabled={busy} /></label>
      <label>{kind === 'DUEL' ? 'Opponent' : 'Invite a member (optional)'}
        <select name="invitee" required={kind === 'DUEL'} disabled={busy}>
          <option value="">{kind === 'DUEL' ? 'Choose a member' : 'Open enrollment'}</option>
          {members.filter(member => member.id !== accountId)
            .map(member => <option key={member.id} value={member.id}>{member.displayName}</option>)}
        </select>
      </label>
      <label>Scoring rule
        <select value={rule} disabled={busy} onChange={event => setRule(event.target.value as ChallengeRule)}>
          <option value="COUNT">Distinct solve count</option>
          <option value="PROBLEM">Specific problem</option>
          <option value="DIFFICULTY">Difficulty goal</option>
        </select>
      </label>
      {rule === 'PROBLEM' ? <label>Problem slug
        <input name="slug" required pattern="[a-z0-9]+(-[a-z0-9]+)*" maxLength={128} disabled={busy}
          placeholder="two-sum" />
      </label> : <label>Solve target<input name="target" type="number" required min={1} max={100} defaultValue={1} disabled={busy} /></label>}
      {rule === 'DIFFICULTY' && <label>Difficulty
        <select name="difficulty" disabled={busy}><option value="EASY">Easy</option><option value="MEDIUM">Medium</option>
          <option value="HARD">Hard</option></select>
      </label>}
      <div className="group-fields">
        <label>Start (UTC, optional)<input name="startsAt" type="datetime-local" disabled={busy} /></label>
        <label>Deadline (UTC)<input name="endsAt" type="datetime-local" required disabled={busy} /></label>
      </div>
      <small>Leave the start blank to open immediately. Eligible solves can sync for 24 hours after the deadline.</small>
      <button type="submit" disabled={busy}>Publish {kind === 'DUEL' ? 'duel' : 'challenge'}</button>
    </form>
  </details>
}

function ChallengeDetails({ selected, busy, onEnroll, onClose }: {
  selected: ChallengeView
  busy: boolean
  onEnroll: () => void
  onClose: () => void
}) {
  const { challenge, participants } = selected
  return <section className="competition-result" aria-labelledby="challenge-heading">
    <button type="button" className="text-button" onClick={onClose}>Back to competitions</button>
    <h3 id="challenge-heading">{challenge.title}</h3>
    <p>{challenge.kind === 'DUEL' ? 'Duel' : 'Group challenge'} · {statuses[challenge.status]}
      {challenge.outcome && ' · ' + outcomes[challenge.outcome]}</p>
    <p>{ruleText(challenge)}. The highest score up to {challenge.target} shares first place if tied.</p>
    <dl className="competition-dates">
      <div><dt>Starts</dt><dd>{timestamp(challenge.startsAt)}</dd></div>
      <div><dt>Deadline</dt><dd>{timestamp(challenge.endsAt)}</dd></div>
      <div><dt>Sync cutoff</dt><dd>{timestamp(challenge.cutoffAt)}</dd></div>
    </dl>
    <ul className="group-list">{participants.map(participant => <li key={participant.id}>
      <span><strong>{participant.displayName}</strong><small>@{participant.handle}</small></span>
      <span>{participant.enrolledAt ? `${participant.score} of ${challenge.target}` : 'Invited'}
        {participant.winner && <small>{challenge.outcome === 'TIE' ? 'Tied for first' : 'Winner'}</small>}</span>
    </li>)}</ul>
    {selected.canEnroll && <button type="button" className="text-button" disabled={busy} onClick={onEnroll}>
      {challenge.kind === 'DUEL' ? 'Accept duel' : 'Enroll in challenge'}
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
  const [activityOpen, setActivityOpen] = useState(false)
  const [challenges, setChallenges] = useState<Challenge[] | null>(null)
  const [feed, setFeed] = useState<FeedItem[] | null>(null)
  const [selectedId, setSelectedId] = useState(initialChallengeId)
  const [selected, setSelected] = useState<ChallengeView | null>(null)
  const [browsingOlder, setBrowsingOlder] = useState(false)
  const [more, setMore] = useState(false)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [revision, setRevision] = useState(0)

  useEffect(() => {
    if (!opened && !activityOpen) return
    let keep = true
    let waiting = false
    async function load() {
      if (waiting) return
      waiting = true
      try {
        const [next, recent, current] = await Promise.all([
          opened && !browsingOlder ? competitionApi.list(accountId, group, 'live') : null,
          activityOpen ? competitionApi.feed(accountId, group, 'live') : null,
          opened && selectedId ? competitionApi.detail(accountId, group, selectedId) : null,
        ])
        if (!keep) return
        if (next) {
          setChallenges(next)
          setMore(next.length === 30)
        }
        if (recent) setFeed(recent)
        if (current) setSelected(current)
        setError('')
      } catch (failure) {
        if (keep) {
          setError(message(failure))
          if (failure instanceof ApiError && failure.status === 404) {
            setSelected(null)
            setChallenges(null)
            setFeed(null)
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
  }, [accountId, group, opened, activityOpen, browsingOlder, selectedId, revision])

  function openChallenge(id: string | null) {
    setSelected(null)
    setSelectedId(id)
    setError('')
    if (id) setOpened(true)
  }

  async function submit(draft?: ChallengeDraft): Promise<boolean> {
    setBusy(true)
    setError('')
    try {
      const entry = draft ? await competitionApi.create(accountId, group, draft)
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

  return <section className="group-tools" aria-label="Group competition and activity">
    {error && <p className="notice error" role="alert">{error}</p>}
    <details open={opened} onToggle={event => setOpened(event.currentTarget.open)}>
      <summary>Challenges and duels</summary>
      {opened && (selectedId ? selected
        ? <ChallengeDetails selected={selected} busy={busy} onEnroll={() => void submit()} onClose={() => openChallenge(null)} />
        : error ? <button type="button" className="text-button" onClick={() => openChallenge(null)}>Back to competitions</button>
          : <p role="status">Loading competition…</p>
        : <>
          {!challenges ? !error && <p role="status">Loading competitions…</p> : challenges.length
            ? <ul className="group-list">{challenges.map(entry => <li key={entry.id}>
                <span><button type="button" className="text-button" onClick={() => openChallenge(entry.id)}>{entry.title}</button>
                  <small>{entry.kind === 'DUEL' ? 'Duel' : 'Group challenge'} · {statuses[entry.status]}</small></span>
                <small>{entry.enrolled} enrolled</small>
              </li>)}</ul>
            : <p className="muted">No competitions yet.</p>}
          {more && <button type="button" className="text-button" disabled={busy} onClick={() => void older()}>Older competitions</button>}
          <ChallengeForm accountId={accountId} members={detail.members} owner={detail.group.role === 'OWNER'}
            onCreate={submit} />
        </>)}
    </details>
    <details onToggle={event => setActivityOpen(event.currentTarget.open)}>
      <summary>Group activity</summary>
      {activityOpen && (!feed ? !error && <p role="status">Loading activity…</p> : feed.length
        ? <ul className="group-list">{feed.map((entry, index) => <li key={entry.kind + entry.at + index}>
            <span>{entry.kind === 'SOLVE'
              ? <><strong>{entry.displayName}</strong> solved {entry.label} · +{entry.xp} base XP</>
              : <>{entry.kind === 'CHALLENGE_CREATED' ? 'Competition opened' : 'Result available'} · <button type="button"
                className="text-button" onClick={() => openChallenge(entry.challengeId)}>{entry.label}</button></>}
              <small>{timestamp(entry.at)}</small></span>
          </li>)}</ul>
        : <p className="muted">No group activity yet.</p>)}
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
