import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { ApiError, groupsApi, rewardsApi } from './api'
import type { Group, GroupFeatured, GroupInvite, GroupMember, GroupMetrics, PublicGroup, RewardsProgress, Standings, StandingsPeriod } from './api'
import { Competition } from './Competition'
import { GroupQuota, GroupRank, OvertakeCalculator, SquadronRoster } from './GroupDeck'
import { Avatar, Icon } from './Icons'
import { LeaderboardStandings } from './LeaderboardStandings'
import { GroupDirectoryCard, PublicDirectoryCard } from './GroupDirectoryCard'
import './group-directory.css'

function message(error: unknown) {
  return error instanceof ApiError ? error.message : 'LeetGrind could not be reached. Try again.'
}

function settings(form: HTMLFormElement) {
  const data = new FormData(form)
  return { name: String(data.get('name') ?? ''), description: String(data.get('description') ?? '') }
}

function GroupSettings({ group, busy }: { group?: Group; busy: boolean }) {
  return <>
    <label>Group name
      <input name="name" required maxLength={80} defaultValue={group?.name} disabled={busy} />
    </label>
    <label>Description <span className="optional">(optional)</span>
      <textarea name="description" rows={2} maxLength={280} defaultValue={group?.description} disabled={busy} />
    </label>
  </>
}

type Selection = {
  accountId: string
  selectedId: string | null
  challengeId: string | null
  onSelect: (id: string | null) => void
  onPractice: () => void
}
type Directory = Awaited<ReturnType<typeof groupsApi.list>>
type GroupTool = 'create' | 'invite' | 'explore'
const groupFilters = {
  all: { label: 'All groups', matches: (_group: Group) => true },
  owned: { label: 'Owned', matches: (group: Group) => group.role === 'OWNER' },
  private: { label: 'Private', matches: (group: Group) => group.visibility === 'PRIVATE' },
  public: { label: 'Public', matches: (group: Group) => group.visibility === 'PUBLIC' },
}
const filterOptions = Object.keys(groupFilters) as (keyof typeof groupFilters)[]

export function Groups({ accountId, selectedId, challengeId, onSelect, onPractice }: Selection) {
  const [directory, setDirectory] = useState<Directory | null>(null)
  const [metrics, setMetrics] = useState<GroupMetrics[] | null>(null)
  const [publicGroups, setPublicGroups] = useState<PublicGroup[] | null>(null)
  const [publicMore, setPublicMore] = useState(false)
  const [showDiscoveries, setShowDiscoveries] = useState(false)
  const [filter, setFilter] = useState<keyof typeof groupFilters>('all')
  const [query, setQuery] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [tool, setTool] = useState<GroupTool | null>(null)
  const toolDialog = useRef<HTMLDialogElement>(null)
  const discoveries = useRef<HTMLElement>(null)

  function openGroup(id: string) {
    toolDialog.current?.close()
    setTool(null)
    onSelect(id)
    window.scrollTo({ top: 0 })
  }

  useEffect(() => {
    let keep = true
    groupsApi.list(accountId).then(result => { if (keep) setDirectory(result) })
      .catch(failure => { if (keep) setError(message(failure)) })
    if (!selectedId) {
      groupsApi.discover(accountId).then(entries => {
        if (keep) {
          setPublicGroups(entries)
          setPublicMore(entries.length === 50)
        }
      }).catch(failure => { if (keep) { setPublicGroups([]); setError(message(failure)) } })
    }
    return () => { keep = false }
  }, [accountId, selectedId])

  useEffect(() => {
    if (selectedId) return
    let keep = true
    let waiting = false
    async function refresh() {
      if (waiting || document.visibilityState !== 'visible') return
      waiting = true
      try {
        const entries = await groupsApi.overview(accountId)
        if (keep) setMetrics(entries)
      } catch {
        if (keep) setMetrics(previous => previous ?? [])
      } finally {
        waiting = false
      }
    }
    void refresh()
    const timer = window.setInterval(refresh, 8_000)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      keep = false
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [accountId, selectedId])

  useEffect(() => {
    if (selectedId || !tool) return
    if (tool === 'explore') {
      setShowDiscoveries(true)
      discoveries.current?.scrollIntoView({ block: 'start' })
      discoveries.current?.focus({ preventScroll: true })
      setTool(null)
    } else if (!toolDialog.current?.open) {
      toolDialog.current?.showModal()
    }
  }, [selectedId, tool])

  function openTool(next: GroupTool) {
    setError('')
    setTool(next)
  }

  async function choose(event: FormEvent<HTMLFormElement>, create: boolean) {
    event.preventDefault()
    const form = event.currentTarget
    setBusy(true)
    setError('')
    try {
      const group = create
        ? await groupsApi.create(accountId, {
          ...settings(form), visibility: new FormData(form).get('visibility') === 'PUBLIC' ? 'PUBLIC' : 'PRIVATE',
        })
        : await groupsApi.join(accountId, String(new FormData(form).get('code') ?? ''))
      openGroup(group.id)
    } catch (failure) {
      setError(message(failure))
    } finally {
      setBusy(false)
    }
  }

  async function discover(before?: string, joinId?: string) {
    setBusy(true)
    setError('')
    try {
      if (joinId) {
        const group = await groupsApi.joinPublic(accountId, joinId)
        openGroup(group.id)
      } else {
        const entries = await groupsApi.discover(accountId, before)
        setPublicGroups(previous => [...(previous ?? []), ...entries])
        setPublicMore(entries.length === 50)
      }
    } catch (failure) {
      setError(message(failure))
    } finally {
      setBusy(false)
    }
  }

  if (selectedId) return (
    <GroupView key={selectedId} accountId={accountId} id={selectedId}
      groups={directory?.items ?? []} challengeId={challengeId}
      onSelect={openGroup} onPractice={onPractice} onBack={next => {
        setTool(next ?? null)
        onSelect(null)
        window.scrollTo({ top: 0 })
      }} />
  )

  const groups = directory?.items ?? []
  const search = query.trim().toLowerCase()
  const filtered = groups.filter(group => groupFilters[filter].matches(group)
    && (group.name.toLowerCase().includes(search) || group.description.toLowerCase().includes(search)))
  const metricsByGroup = new Map(metrics?.map(entry => [entry.id, entry]))
  const publicPreview = showDiscoveries ? publicGroups : publicGroups?.slice(0, 2)
  const formOpen = tool === 'create' || tool === 'invite'

  return <div className="group-directory">
    <header className="directory-hero">
      <div>
        <div className="directory-hero-heading">
          <h1>Your groups</h1>
          {directory && <span className="directory-count">{groups.length} {groups.length === 1 ? 'group' : 'groups'}</span>}
        </div>
        <p>Track weekly solves and XP across your groups.</p>
      </div>
      <div className="directory-actions">
        <button type="button" className="directory-action directory-action-primary" onClick={() => openTool('create')}>
          <Icon name="add" />Create a group
        </button>
        <button type="button" className="directory-action" onClick={() => openTool('invite')}>
          <Icon name="group" />Join with invite code
        </button>
        <button type="button" className="directory-action" onClick={() => openTool('explore')}>
          <Icon name="public" />Find public groups
        </button>
      </div>
    </header>

    {error && !formOpen && <p className="notice error" role="alert">{error}</p>}
    {directory ? <>
      <div className="directory-controls">
        <div className="directory-filter-tabs" role="group" aria-label="Group filters">
          {filterOptions.map(option => <button key={option} type="button" aria-pressed={filter === option}
            onClick={() => setFilter(option)}>
            {groupFilters[option].label} ({groups.filter(groupFilters[option].matches).length})
          </button>)}
        </div>
        <label className="directory-search">
          <Icon name="search" />
          <span className="visually-hidden">Filter groups</span>
          <input type="search" placeholder="Filter groups…" value={query} onChange={event => setQuery(event.target.value)} />
        </label>
      </div>
      {filtered.length ? <ul className="directory-grid" aria-label="Joined groups">
        {filtered.map(group => <li key={group.id}>
          <GroupDirectoryCard group={group} metrics={metricsByGroup.get(group.id)} loading={!metrics} onOpen={() => openGroup(group.id)} />
        </li>)}
      </ul> : <p className="directory-empty" role="status">
        {groups.length ? 'No groups match your filters.' : 'You haven’t joined a group yet.'}
      </p>}
    </> : !error && <p role="status">Loading groups…</p>}

    <section ref={discoveries} tabIndex={-1} className="directory-discoveries" aria-labelledby="directory-public-heading">
      <header>
        <div>
          <h2 id="directory-public-heading"><Icon name="bolt" /> Discover public groups</h2>
          <p>Join a public group to practice with its members.</p>
        </div>
        {!showDiscoveries && (publicMore || (publicGroups?.length ?? 0) > 2)
          && <button type="button" className="text-button" onClick={() => openTool('explore')}>View all discoveries <Icon name="arrow_forward" /></button>}
      </header>
      {publicPreview?.length ? <ul className="directory-grid">{publicPreview.map(group => <li key={group.id}>
        <PublicDirectoryCard group={group} busy={busy} onJoin={() => void discover(undefined, group.id)} />
      </li>)}</ul> : <p className="directory-empty" role="status">
        {publicPreview ? 'No public groups available.' : 'Loading public groups…'}
      </p>}
      {showDiscoveries && publicMore && <button type="button" className="text-button directory-more" disabled={busy}
        onClick={() => void discover(publicGroups?.at(-1)?.id)}>More public groups</button>}
    </section>

    <dialog ref={toolDialog} className="orbital-dialog directory-dialog" aria-labelledby="group-tool-heading" onClose={() => {
      setTool(null)
      setError('')
    }}>
      <header className="deck-header">
        <h2 id="group-tool-heading">{tool === 'create' ? 'Create a group' : 'Join with invite code'}</h2>
        <button type="button" className="icon-button" aria-label="Close group form" onClick={() => toolDialog.current?.close()}><Icon name="close" /></button>
      </header>
      {error && formOpen && <p className="notice error" role="alert">{error}</p>}
      {formOpen && <form key={tool} onSubmit={event => void choose(event, tool === 'create')}>
        {tool === 'create' ? <>
          <GroupSettings busy={busy} />
          <label>Visibility
            <select name="visibility" defaultValue="PRIVATE" disabled={busy}>
              <option value="PRIVATE">Private · invitation required</option>
              <option value="PUBLIC">Public · open to signed-in users</option>
            </select>
          </label>
        </> : <label>Invitation code
          <input name="code" required autoComplete="off" maxLength={64} disabled={busy} />
        </label>}
        <button type="submit" disabled={busy}>{tool === 'create' ? 'Create group' : 'Join group'}</button>
      </form>}
    </dialog>
  </div>
}

type GroupInfo = Awaited<ReturnType<typeof groupsApi.details>>

function GroupView({ accountId, id, groups, challengeId, onBack, onSelect, onPractice }: {
  accountId: string
  id: string
  groups: Group[]
  challengeId: string | null
  onBack: (tool?: GroupTool) => void
  onSelect: (id: string) => void
  onPractice: () => void
}) {
  const [detail, setDetail] = useState<GroupInfo | null>(null)
  const [invites, setInvites] = useState<GroupInvite[]>([])
  const [newCode, setNewCode] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [unavailable, setUnavailable] = useState(false)

  function fail(failure: unknown) {
    if (failure instanceof ApiError && failure.status === 404) {
      setUnavailable(true)
      setDetail(null)
      setInvites([])
      setNewCode('')
    }
    setError(message(failure))
  }

  useEffect(() => {
    let keep = true
    groupsApi.details(accountId, id).then(async next => {
      if (!keep) return
      setDetail(next)
      if (next.group.role === 'OWNER') {
        const entries = await groupsApi.invites(accountId, id)
        if (keep) setInvites(entries)
      }
    }).catch(failure => { if (keep) fail(failure) })
    return () => { keep = false }
  }, [accountId, id])

  async function perform(task: () => Promise<void>) {
    setBusy(true)
    setError('')
    try {
      await task()
    } catch (failure) {
      fail(failure)
    } finally {
      setBusy(false)
    }
  }

  async function refreshDetails() {
    setDetail(await groupsApi.details(accountId, id))
  }

  function update(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const next = settings(event.currentTarget)
    void perform(async () => {
      await groupsApi.update(accountId, id, next)
      await refreshDetails()
    })
  }

  function generate(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const data = new FormData(event.currentTarget)
    void perform(async () => {
      const invite = await groupsApi.invite(accountId, id, {
        expiresInHours: Number(data.get('hours')),
        maxUses: Number(data.get('maxUses')),
      })
      setNewCode(invite.code)
      setInvites(await groupsApi.invites(accountId, id))
    })
  }

  return <>
    <button type="button" className="text-button squad-back" onClick={() => onBack()}>Back to groups</button>
    {error && <p className="notice error" role="alert">{error}</p>}
    {!detail ? (!unavailable && !error && <p role="status">Loading group…</p>) : <>
      <header className="squad-context">
        <div>
          <h1><span className="signal-dot signal-blue" aria-hidden="true" />{detail.group.name}</h1>
          {detail.group.description && <p className="muted">{detail.group.description}</p>}
        </div>
        {groups.length > 1 && <label className="squad-switch">Switch squad
          <select value={id} onChange={event => onSelect(event.target.value)}>
            {groups.map(group => <option key={group.id} value={group.id}>{group.name}</option>)}
          </select>
        </label>}
        <div className="squad-context-actions">
          <button type="button" className="deck-secondary" onClick={() => onBack('create')}><Icon name="add" /> Create squad</button>
          <button type="button" className="deck-secondary" onClick={() => onBack('explore')}><Icon name="search" /> Explore groups</button>
        </div>
      </header>
      <GroupStandings accountId={accountId} id={id} groupName={detail.group.name} members={detail.members} onPractice={onPractice}
        onAccessLost={() => fail(new ApiError(404, 'This group is unavailable.'))} />
      <div className="deck-panel squad-competition">
        <Competition accountId={accountId} detail={detail} initialChallengeId={challengeId} />
      </div>
      <div className="group-tools">
        <details>
          <summary>Members ({detail.members.length})</summary>
          <ul className="group-list">
            {detail.members.map(member => <li key={member.id}>
              <span><strong>{member.displayName}</strong><small>@{member.handle} · {member.role === 'OWNER' ? 'Owner' : 'Member'}</small></span>
              {detail.group.role === 'OWNER' && member.id !== accountId && (
                <span className="group-actions">
                  <button type="button" className="text-button" disabled={busy}
                    aria-label={'Make ' + member.displayName + ' owner'} onClick={() => {
                      if (!window.confirm('Transfer ownership to ' + member.displayName + '?')) return
                      void perform(async () => {
                        await groupsApi.transfer(accountId, id, member.id)
                        setNewCode('')
                        setInvites([])
                        await refreshDetails()
                      })
                    }}>Make owner</button>
                  <button type="button" className="text-button" disabled={busy}
                    aria-label={'Remove ' + member.displayName} onClick={() => void perform(async () => {
                      await groupsApi.remove(accountId, id, member.id)
                      await refreshDetails()
                    })}>Remove</button>
                </span>
              )}
            </li>)}
          </ul>
          {detail.group.role === 'MEMBER' && <button type="button" className="text-button" disabled={busy}
            onClick={() => void perform(async () => {
              await groupsApi.remove(accountId, id, accountId)
              onBack()
            })}>Leave group</button>}
        </details>
        {detail.group.role === 'OWNER' && <>
          <details open>
            <summary>Invitations</summary>
            <form onSubmit={generate}>
              <div className="group-fields">
                <label>Expires in (hours)<input name="hours" type="number" min={1} max={168} required defaultValue={24} disabled={busy} /></label>
                <label>Maximum uses<input name="maxUses" type="number" min={1} max={100} required defaultValue={1} disabled={busy} /></label>
              </div>
              <button type="submit" disabled={busy}>Create invitation</button>
            </form>
            {newCode && <label className="invite-code">New invitation code
              <input value={newCode} readOnly onFocus={event => event.currentTarget.select()} />
              <small>Share this code with people you want to invite. It’s only shown here after creation.</small>
            </label>}
            {!!invites.length && <ul className="group-list">
              {invites.map(invite => <li key={invite.id}>
                <span>{invite.uses} of {invite.maxUses} uses<small>
                  {invite.revokedAt ? 'Revoked' : 'Expires ' + new Date(invite.expiresAt).toLocaleString()}
                </small></span>
                {!invite.revokedAt && <button type="button" className="text-button" disabled={busy}
                  onClick={() => void perform(async () => {
                    await groupsApi.revoke(accountId, id, invite.id)
                    setNewCode('')
                    setInvites(await groupsApi.invites(accountId, id))
                  })}>Revoke</button>}
              </li>)}
            </ul>}
          </details>
          <details>
            <summary>Group settings</summary>
            <form onSubmit={update}>
              <GroupSettings key={detail.group.name + '\n' + detail.group.description} group={detail.group} busy={busy} />
              <button type="submit" disabled={busy}>Save group</button>
            </form>
            <button type="button" className="text-button close-group" disabled={busy} onClick={() => {
              if (!window.confirm('Close this group and end all current memberships?')) return
              void perform(async () => { await groupsApi.close(accountId, id); onBack() })
            }}>Close group</button>
          </details>
        </>}
      </div>
    </>}
  </>
}

export function GroupStandings({ accountId, id, groupName, onAccessLost, members, onPractice }: {
  accountId: string
  id: string | null
  groupName?: string
  onAccessLost: () => void
  members?: GroupMember[]
  onPractice?: () => void
}) {
  const squad = members !== undefined
  const [period, setPeriod] = useState<StandingsPeriod>('weekly')
  const [date, setDate] = useState('')
  const [page, setPage] = useState<Standings | null>(null)
  const [featured, setFeatured] = useState<GroupFeatured | null>(null)
  const [intercept, setIntercept] = useState<RewardsProgress['featured'] | null>(null)
  const [revision, setRevision] = useState(0)
  const [pages, setPages] = useState(1)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [unavailable, setUnavailable] = useState(false)

  function failed(failure: unknown) {
    if (failure instanceof ApiError && failure.status === 404) {
      setUnavailable(true)
      onAccessLost()
    } else {
      setError(message(failure))
    }
  }

  useEffect(() => {
    if (unavailable) return
    let keep = true
    let waiting = false
    async function loadStandings() {
      const count = pages * 20
      let result = await groupsApi.standings(accountId, id, 'live', period, date, undefined, Math.min(count, 100))
      if (count <= 100 || !result.nextCursor) return result
      const start = result.periodStart?.slice(0, period === 'monthly' ? 7 : 10) || ''
      const items = new Map(result.items.map(item => [item.id, item]))
      for (let remaining = count - 100; keep && remaining > 0 && result.nextCursor; remaining -= 100) {
        result = await groupsApi.standings(accountId, id, 'live', period, start, result.nextCursor, Math.min(remaining, 100))
        for (const item of result.items) items.set(item.id, item)
      }
      return { ...result, items: [...items.values()].sort((first, second) => first.rank - second.rank) }
    }
    async function load() {
      if (waiting) return
      waiting = true
      try {
        const extras = squad && id ? rewardsApi.progress(accountId, 'live').catch(() => null) : null
        const [result, activity] = await Promise.all([
          loadStandings(),
          id ? rewardsApi.groupFeatured(accountId, id, 'live').catch(() => null) : null,
        ])
        if (keep) {
          setPage(result)
          setFeatured(activity)
          setError('')
        }
        if (extras) {
          const personal = await extras
          if (keep) {
            setIntercept(personal?.featured.title === activity?.title ? personal?.featured ?? null : null)
          }
        }
      } catch (failure) {
        if (keep) failed(failure)
      } finally {
        waiting = false
        if (keep) setBusy(false)
      }
    }
    void load()
    function refreshVisible() {
      if (document.visibilityState === 'visible') void load()
    }
    const timer = window.setInterval(refreshVisible, 8_000)
    document.addEventListener('visibilitychange', refreshVisible)
    return () => {
      keep = false
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', refreshVisible)
    }
  }, [accountId, id, period, date, revision, pages, unavailable, squad])

  function reset(newPeriod: StandingsPeriod, newDate = '') {
    setPage(null)
    setFeatured(null)
    setIntercept(null)
    setPages(1)
    setPeriod(newPeriod)
    setDate(newDate)
    setRevision(value => value + 1)
  }

  function loadMore() {
    if (!page?.nextCursor) return
    setBusy(true)
    setError('')
    setPages(value => value + 1)
  }

  function previousPeriod() {
    if (!page?.periodStart) return
    const start = new Date(page.periodStart)
    if (period === 'monthly') start.setUTCMonth(start.getUTCMonth() - 1)
    else start.setUTCDate(start.getUTCDate() - 7)
    reset(period, start.toISOString().slice(0, period === 'monthly' ? 7 : 10))
  }

  const title = period === 'monthly' ? 'Monthly' : period === 'weekly' ? 'Weekly' : 'All-time'
  const unit = period === 'monthly' ? 'month' : 'week'
  const controls = <>
    <label>Standings period
      <select value={period} disabled={busy} onChange={event => reset(event.target.value as StandingsPeriod)}>
        <option value="weekly">Weekly</option>
        <option value="monthly">Monthly</option>
        <option value="all-time">All time</option>
      </select>
    </label>
  </>
  const actions = <div className="group-actions">
    {period !== 'all-time' && <button type="button" className="text-button" disabled={busy || !page?.periodStart} onClick={previousPeriod}>Previous {unit}</button>}
    {date && <button type="button" className="text-button" disabled={busy} onClick={() => reset(period)}>This {unit}</button>}
    <button type="button" className="text-button" disabled={busy} onClick={() => setRevision(value => value + 1)}>Refresh standings</button>
  </div>
  const periodLabel = page?.periodStart && page.periodEnd
    ? `${page.periodStart.slice(0, 10)} to ${page.periodEnd.slice(0, 10)} at 00:00 ${page.timezone}`
    : id ? 'All eligible membership periods' : 'All time'
  const loadMoreButton = page?.nextCursor && <button type="button" className="text-button" disabled={busy}
    onClick={loadMore}>Load more</button>

  if (members) {
    const leader = page?.items[0]
    const own = page?.items.find(row => row.id === accountId)
    return <section className="squad-standings" aria-labelledby="standings-heading">
      <div className="squad-filters"><h2 id="standings-heading">{title} standings</h2><div>{controls}</div></div>
      {error && <p className="notice error" role="alert">{error}</p>}
      {!page ? !error && <p role="status">Loading standings…</p> : <>
        <GroupRank leader={leader} own={own} featured={featured} intercept={intercept} onPractice={onPractice} />
        <div className="squad-workstation">
          <div className="squad-roster-area">
            <SquadronRoster items={page.items} members={members} own={own} title={title} />
            <div className="squad-roster-footer"><p className="muted">{periodLabel}</p>
              {actions}{loadMoreButton}</div>
          </div>
          <aside className="squad-rail" aria-label="Squad progress">
            <GroupQuota featured={featured} />
            <OvertakeCalculator leader={leader} own={own} />
          </aside>
        </div>
      </>}
    </section>
  }

  return <LeaderboardStandings groupName={groupName} accountId={accountId} page={page} period={period}
    onPeriod={next => { if (next !== period) reset(next) }} actions={actions}
    periodLabel={periodLabel} loadMore={loadMoreButton} busy={busy} error={error}
    featured={featured && <span>Today’s featured problem · {featured.title} · {featured.completed} of {featured.members} members completed</span>} />
}
