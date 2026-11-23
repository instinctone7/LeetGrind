import type { Group, GroupMetrics, PublicGroup } from './api'
import { Avatar, Icon } from './Icons'

const number = new Intl.NumberFormat()
const utcDate = new Intl.DateTimeFormat(undefined, {
  month: 'short', day: 'numeric', hour: 'numeric', minute: '2-digit', timeZone: 'UTC',
})

function GroupBadge({ name }: { name: string }) {
  const initials = name.trim().split(/\s+/).slice(0, 2).map(word => Array.from(word)[0]).join('')
  return <span className="directory-badge" aria-hidden="true">{initials.toUpperCase()}</span>
}

export function GroupDirectoryCard({ group, metrics, loading, onOpen }: {
  group: Group; metrics?: GroupMetrics; loading: boolean; onOpen: () => void
}) {
  const publicGroup = group.visibility === 'PUBLIC'
  const initials = metrics ? Array.from(metrics.memberInitials) : []
  const extra = (metrics?.memberCount ?? 0) - initials.length
  return <article className="directory-card" data-public={publicGroup}>
    <header className="directory-card-header">
      <GroupBadge name={group.name} />
      <div>
        <div className="directory-card-title">
          <h2>{group.name}</h2>
          <span className="directory-role">{group.role === 'OWNER' ? 'Owner' : 'Member'}</span>
        </div>
        <p>{publicGroup ? <Icon name="public" /> : <span className="directory-lock" aria-hidden="true" />}
          {publicGroup ? 'Public community' : 'Private workspace'}
          <span className="directory-group-id"> · #{group.id}</span></p>
      </div>
      <button type="button" className="directory-open" aria-label={group.name} onClick={onOpen}>
        <Icon name="arrow_forward" />
      </button>
    </header>
    {group.description && <p className="directory-description">{group.description}</p>}
    <dl className="directory-metrics">
      <div><dt>Weekly XP</dt><dd>{metrics ? number.format(metrics.weeklyXp) : '—'}</dd></div>
      <div><dt>Solves this week</dt><dd>{metrics ? number.format(metrics.weeklySolves) : '—'}</dd></div>
      <div><dt>Your XP</dt><dd>{metrics ? number.format(metrics.personalXp) : '—'}</dd></div>
    </dl>
    <footer className="directory-card-footer">
      {metrics ? <>
        <div className="directory-roster">
          {initials.map((initial, index) => <Avatar key={index} name={initial} className={`tone-${index}`} />)}
          {extra > 0 && <span className="orbital-avatar" aria-hidden="true">+{number.format(extra)}</span>}
          <span>{number.format(metrics.memberCount)} {metrics.memberCount === 1 ? 'member' : 'members'}</span>
        </div>
        <span>{metrics.lastSolveAt ? <>Last solve <time dateTime={metrics.lastSolveAt}>{utcDate.format(new Date(metrics.lastSolveAt))} UTC</time></> : 'No solves this week'}</span>
      </> : <span>{loading ? 'Loading activity…' : 'Group metrics unavailable'}</span>}
    </footer>
  </article>
}

export function PublicDirectoryCard({ group, busy, onJoin }: { group: PublicGroup; busy: boolean; onJoin: () => void }) {
  return <article className="directory-discovery-card">
    <GroupBadge name={group.name} />
    <div>
      <h3>{group.name}</h3>
      {group.description && <p>{group.description}</p>}
    </div>
    <button type="button" className="directory-action" disabled={busy} aria-label={'Join ' + group.name} onClick={onJoin}>Join group</button>
  </article>
}
