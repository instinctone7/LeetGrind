import { useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import type { Challenge, DuelDraft, DuelSummary, GroupMember } from './api'
import { Icon } from './Icons'
import './duels.css'

function signedXp(amount: number) {
  return (amount > 0 ? '+' : '') + amount + ' XP'
}

export function DuelClock({ deadline }: { deadline: string }) {
  const [now, setNow] = useState(Date.now)
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 1_000)
    return () => window.clearInterval(timer)
  }, [])
  const seconds = Math.max(0, Math.ceil((Date.parse(deadline) - now) / 1_000))
  const time = [Math.floor(seconds / 60), seconds % 60].map(part => String(part).padStart(2, '0')).join(':')
  return <span className="duel-clock" data-urgent={seconds <= 120}>{seconds ? time + ' remaining' : 'Time expired'}</span>
}

export function DuelArena({ accountId, groupName, members, entries, summary, busy, onOpen, onCreate }: {
  accountId: string
  groupName: string
  members: GroupMember[]
  entries: Challenge[]
  summary: DuelSummary | null
  busy: boolean
  onOpen: (id: string) => void
  onCreate: (draft: DuelDraft) => Promise<boolean>
}) {
  const [stake, setStake] = useState(5)
  const recent = entries.filter(duel => duel.status === 'FINALIZED')
  const active = entries.filter(duel => duel.status !== 'FINALIZED')
  const rivals = members.filter(member => member.id !== accountId)

  async function send(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const form = event.currentTarget
    const input = new FormData(form)
    if (await onCreate({
      inviteeId: String(input.get('opponent')), problemUrl: String(input.get('problem')),
      durationMinutes: Number(input.get('duration')), stakeXp: stake,
    })) form.reset()
  }

  return <section className="duel-arena" aria-labelledby="duel-arena-heading">
    <header className="duel-header">
      <div><h2 id="duel-arena-heading">1v1 Arena</h2><p>{groupName}</p></div>
      <dl className="duel-stats">
        <div data-tone="win"><dt>Duels won</dt><dd>{summary?.wins ?? '—'}</dd></div>
        <div data-tone="loss"><dt>Duels lost</dt><dd>{summary?.losses ?? '—'}</dd></div>
        <div><dt>Net duel XP</dt><dd>{summary ? signedXp(summary.netXp) : '—'}</dd></div>
      </dl>
    </header>

    <section className="duel-composer" aria-labelledby="duel-compose-heading">
      <header><h3 id="duel-compose-heading"><span className="duel-emblem"><Icon name="code" /></span>Instant 1v1 Challenge</h3>
        {summary && <p>Available XP <strong>{summary.availableXp}</strong></p>}
      </header>
      <form onSubmit={event => void send(event)}>
        <label>Opponent<select name="opponent" required disabled={busy || !rivals.length}>
          <option value="">Choose a member</option>
          {rivals.map(member => <option key={member.id} value={member.id}>{member.displayName}</option>)}
        </select></label>
        <label>LeetCode problem URL<input type="url" name="problem" required maxLength={300}
          placeholder="https://leetcode.com/problems/two-sum/" disabled={busy} /></label>
        <label>Duration (minutes)<input name="duration" type="number" min={10} max={120} step={1}
          required defaultValue={25} disabled={busy} /></label>
        <fieldset><legend>XP stake</legend><div className="duel-stakes">
          {[5, 25, 50, 100].map(amount => <button type="button" key={amount} aria-pressed={stake === amount}
            disabled={busy || Boolean(summary && amount > summary.availableXp)}
            onClick={() => setStake(amount)}>{amount} XP</button>)}
        </div></fieldset>
        <button type="submit" className="duel-primary" disabled={busy || !rivals.length || Boolean(summary && stake > summary.availableXp)}>
          Send duel
        </button>
      </form>
      <p className="duel-hint">The timer starts when your opponent accepts. Sync the assigned solve before time runs out.</p>
    </section>

    <div className="duel-columns">
      <section aria-labelledby="duel-recent-heading">
        <h3 id="duel-recent-heading">Recent duels</h3>
        {recent.length ? <ul className="duel-list">{recent.map(duel => {
          const xp = duel.xpDelta ?? 0
          const outcome = duel.outcome === 'CANCELLED' ? 'Cancelled' : duel.outcome === 'TIE' ? 'Tie'
            : xp > 0 ? 'Victory' : xp < 0 ? 'Defeat' : 'Finalized'
          return <li className="duel-card" data-tone={xp > 0 ? 'win' : xp < 0 ? 'loss' : undefined} key={duel.id}>
            <div><span className="duel-badge">{outcome}{duel.outcome === 'NO_SCORE' ? ' · Timeout' : ''}</span>
              <button type="button" className="text-button duel-match" onClick={() => onOpen(duel.id)}>{duel.title}</button>
              {duel.opponent && <p>vs {duel.opponent}</p>}
            </div>
            {duel.xpDelta !== null && <strong className="duel-result-xp">{signedXp(xp)}</strong>}
          </li>
        })}</ul> : <p className="duel-empty">No finished duels yet.</p>}
      </section>
      <section aria-labelledby="duel-live-heading">
        <h3 id="duel-live-heading">Live duels</h3>
        {active.length ? <ul className="duel-list">{active.map(duel => <li className="duel-card" key={duel.id}>
          <div>{duel.status === 'ACTIVE' || duel.status === 'GRACE'
            ? <DuelClock deadline={duel.endsAt} />
            : <span className="duel-badge">Awaiting opponent</span>}
            <button type="button" className="text-button duel-match" onClick={() => onOpen(duel.id)}>{duel.title}</button>
            {duel.opponent && <p>vs {duel.opponent}</p>}
          </div>
          {duel.stakeXp != null && <strong className="duel-result-xp">{duel.stakeXp} XP stake</strong>}
        </li>)}</ul> : <p className="duel-empty">No active duels or invitations.</p>}
      </section>
    </div>
  </section>
}
