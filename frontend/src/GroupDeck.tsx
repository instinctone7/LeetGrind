import { useState } from 'react'
import type { GroupFeatured, GroupMember, RewardsProgress, Standings } from './api'
import { Avatar, Icon } from './Icons'

type Standing = Standings['items'][number]
type RankProps = { leader?: Standing; own?: Standing }
const number = new Intl.NumberFormat()
const utc = new Intl.DateTimeFormat(undefined, { month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'UTC' })
const xp = (amount: number) => number.format(amount) + ' XP'

export function GroupRank({ leader, own, featured, intercept, onPractice }: RankProps & {
  featured: GroupFeatured | null
  intercept: RewardsProgress['featured'] | null
  onPractice?: () => void
}) {
  const relative = leader?.xp ? Math.round((own?.xp ?? 0) / leader.xp * 100) : 0
  let title = <>Your place in the squad</>
  if (leader && own) {
    if (leader.xp === 0) title = <>The standings are open</>
    else if (leader.id === own.id) title = <>You lead the squad with <em>{xp(own.xp)}</em></>
    else if (leader.xp === own.xp) title = <>You’re tied with {leader.displayName}</>
    else title = <>Trailing #{leader.rank} {leader.displayName} by <em>{xp(leader.xp - own.xp)}</em></>
  }

  return <section className="deck-panel squad-hero" aria-labelledby="squad-rank-heading">
    <div className="squad-rank">
      {own && <p className="squad-rank-chip deck-kicker"><span className="signal-dot" aria-hidden="true" /> Rank #{own.rank}</p>}
      <h2 id="squad-rank-heading">{title}</h2>
      <p className="muted">{leader && own ? 'Standings count base XP from eligible solves during group membership.'
        : 'Load more roster entries to find your standing.'}</p>
      {own && leader && <div className="squad-gauge">
        <div><span>You <strong>{xp(own.xp)}</strong></span><span>{leader.id === own.id ? 'First place' : 'Leader'} <strong>{xp(leader.xp)}</strong></span></div>
        <progress value={relative} max={100} aria-label="Your XP relative to the leader" />
        <p className="muted">{leader.xp > 0 ? relative + '% of the leader’s XP' : 'No credited solves in this period yet'}</p>
      </div>}
    </div>
    <section className="squad-intercept" aria-labelledby="squad-intercept-heading">
      <div className="squad-card-heading"><h3 id="squad-intercept-heading">Daily Intercept</h3>
        {intercept && <span className="squad-tag">+{xp(intercept.task.rewardXp)} bonus</span>}</div>
      {featured ? <>
        <strong className="squad-intercept-title">{featured.title}</strong>
        {intercept && <p className="deck-kicker">{[intercept.difficulty, ...intercept.topics].join(' · ')}</p>}
        <p className="muted">Closes {utc.format(new Date(featured.endsAt))} UTC</p>
        {intercept?.url ? <a className="deck-primary" href={intercept.url} target="_blank" rel="noreferrer">Solve on LeetCode <Icon name="open_in_new" /></a>
          : onPractice && <button type="button" className="deck-primary" onClick={onPractice}>Open Practice <Icon name="arrow_forward" /></button>}
      </> : <p className="muted">Today’s featured problem is unavailable.</p>}
    </section>
  </section>
}

export function SquadronRoster({ items, members, own, title }: { items: Standing[]; members: GroupMember[]; own?: Standing; title: string }) {
  const roles = new Map(members.map(member => [member.id, member.role]))
  return <section className="squad-roster" aria-labelledby="squad-roster-heading">
    <div className="squad-card-heading"><h3 id="squad-roster-heading">Squadron Roster</h3>
      <span className="squad-tag">{number.format(members.length)} {members.length === 1 ? 'member' : 'members'}</span></div>
    <div className="squad-table-wrap">
      <table>
        <thead><tr><th scope="col">Rank</th><th scope="col">Member</th><th scope="col">{title} XP</th></tr></thead>
        <tbody>{items.map(row => {
          const current = row.id === own?.id
          const delta = own ? row.xp - own.xp : 0
          const role = roles.get(row.id)
          return <tr key={row.id} className={current ? 'squad-current' : undefined}>
            <td><span className="squad-row-rank">{row.rank}</span></td>
            <th scope="row"><div className="squad-member">
              <Avatar name={row.displayName} />
              <span><strong>{row.displayName}</strong>{current && <span className="squad-tag">You</span>}
                <small>@{row.handle}{role === 'OWNER' ? ' · Owner' : ''}</small></span>
            </div></th>
            <td><strong>{xp(row.xp)}</strong>{!current && own && <small>{delta === 0 ? 'Tied on XP' : `${delta > 0 ? '+' : '−'}${xp(Math.abs(delta))} vs you`}</small>}</td>
          </tr>
        })}</tbody>
      </table>
    </div>
  </section>
}

export function GroupQuota({ featured }: { featured: GroupFeatured | null }) {
  return <section className="deck-panel squad-quota">
    <div className="squad-card-heading"><h3><Icon name="task_alt" /> Squad Quota</h3></div>
    <p className="muted">Today’s featured problem</p>
    {featured ? <>
      <strong>{featured.completed} of {featured.members} members completed</strong>
      <progress value={featured.completed} max={Math.max(1, featured.members)} aria-label="Members who completed the featured problem" />
    </> : <p className="muted">Completion data is unavailable.</p>}
  </section>
}

const plans = [
  { label: 'Hard problems', value: 50 },
  { label: 'Medium problems', value: 25 },
  { label: 'Easy problems', value: 10 },
]

export function OvertakeCalculator({ leader, own }: RankProps) {
  const [selection, setSelection] = useState(0)
  const required = leader && own && leader.id !== own.id ? Math.max(1, leader.xp - own.xp + 1) : 0
  return <section className="deck-panel squad-calculator">
    <div className="squad-card-heading"><h3><Icon name="analytics" /> Overtake Calculator</h3></div>
    {own && required > 0 ? <>
      <p className="muted">Earn <strong>{xp(required)}</strong> to pass the leader’s current score.</p>
      <div className="squad-plans" role="group" aria-label="Overtake plan">
        {plans.map((plan, index) => {
          const count = Math.ceil(required / plan.value)
          return <button key={plan.value} type="button" aria-pressed={selection === index} onClick={() => setSelection(index)}>
            <span>{number.format(count)} {count === 1 ? plan.label.slice(0, -1) : plan.label}</span><strong>+{xp(count * plan.value)}</strong>
          </button>
        })}
      </div>
      <p className="squad-projection" role="status">Projected total <strong>{xp(own.xp + Math.ceil(required / plans[selection].value) * plans[selection].value)}</strong></p>
    </> : <p className="muted">{own ? 'You’re currently at the top of the roster.' : 'Find your rank to calculate a path to first place.'}</p>}
  </section>
}
