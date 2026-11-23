import { useEffect, useId, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import { Analytics } from './Analytics'
import { Competition, Inbox } from './Competition'
import { Dashboard } from './Dashboard'
import { Groups, GroupStandings } from './Groups'
import { Avatar, Icon, Wordmark } from './Icons'
import { Practice } from './Practice'
import { Rewards } from './Rewards'
import { RewardToasts } from './RewardToasts'
import { ApiError, groupsApi } from './api'
import type { Group, Profile } from './api'

type View = 'overview' | 'groups' | 'leaderboard' | 'challenges' | 'quests' | 'achievements' | 'analytics' | 'practice' | 'notifications' | 'profile' | 'settings' | 'help'
type Directory = Awaited<ReturnType<typeof groupsApi.list>>
const navigation: { id: View; label: string; icon: string }[] = [
  { id: 'overview', label: 'Overview', icon: 'dashboard' },
  { id: 'groups', label: 'My groups', icon: 'group' },
  { id: 'leaderboard', label: 'Leaderboard', icon: 'bar_chart' },
  { id: 'challenges', label: 'Duels', icon: 'code' },
  { id: 'quests', label: 'Quests', icon: 'task_alt' },
  { id: 'achievements', label: 'Achievements', icon: 'military_tech' },
  { id: 'analytics', label: 'Analytics', icon: 'analytics' },
  { id: 'practice', label: 'Practice', icon: 'bolt' },
]
const accountPages: typeof navigation = [
  { id: 'profile', label: 'Profile', icon: 'person' },
  { id: 'settings', label: 'Settings', icon: 'settings' },
]
const help = { id: 'help' as const, label: 'Help', icon: 'help' }

function WorkspaceNav({ view, displayName, onNavigate }: {
  view: View; displayName: string; onNavigate: (view: View) => void
}) {
  const accountMenuId = useId()
  const accountMenu = useRef<HTMLDivElement>(null)

  function items(entries: typeof navigation, select = onNavigate) {
    return entries.map(item => <button key={item.id} type="button" className="orbital-nav-button"
      aria-current={item.id === view ? 'page' : undefined} title={item.label} onClick={() => select(item.id)}>
      <Icon name={item.icon} /><span>{item.label}</span>
    </button>)
  }
  return <>
    <button type="button" className="sidebar-wordmark" aria-label="LeetGrind overview" onClick={() => onNavigate('overview')}><Wordmark /></button>
    <nav className="orbital-nav" aria-label="Workspace">{items(navigation)}</nav>
    <nav className="orbital-nav orbital-nav-bottom" aria-label="Account and help">
      <button type="button" className="orbital-nav-button account-trigger"
        aria-label="Account menu" title="Account menu" popoverTarget={accountMenuId}
        data-active={view === 'profile' || view === 'settings'}
        onClick={event => {
          const menu = accountMenu.current
          if (!menu) return
          const bounds = event.currentTarget.getBoundingClientRect()
          menu.style.left = `${bounds.left}px`
          menu.style.top = bounds.top < 100 ? `${bounds.bottom + 6}px` : 'auto'
          menu.style.bottom = bounds.top < 100 ? 'auto' : `${window.innerHeight - bounds.top + 6}px`
        }}>
        <Avatar name={displayName} /><span className="account-name">{displayName}</span>
      </button>
      <div id={accountMenuId} ref={accountMenu} popover="auto" className="account-menu">
        {items(accountPages, next => {
          accountMenu.current?.hidePopover()
          onNavigate(next)
        })}
      </div>
      {items([help])}
    </nav>
  </>
}

function GroupEmpty({ onGroups, children }: { onGroups: () => void; children: ReactNode }) {
  return <div className="deck-empty"><Icon name="group" /><p>{children}</p>
    <button className="deck-primary" type="button" onClick={onGroups}>Find or create a group</button></div>
}

function ChallengePage({ profile, group, initialChallengeId }: {
  profile: Profile; group: Group; initialChallengeId: string | null
}) {
  const [details, setDetails] = useState<Awaited<ReturnType<typeof groupsApi.details>> | null>(null)
  const [error, setError] = useState('')
  useEffect(() => {
    let keep = true
    groupsApi.details(profile.id, group.id).then(info => { if (keep) setDetails(info) })
      .catch(failure => { if (keep) setError(failure instanceof ApiError ? failure.message : 'Group details could not be loaded.') })
    return () => { keep = false }
  }, [profile.id, group.id])
  if (error) return <p className="notice error" role="alert">{error}</p>
  if (!details) return <p role="status">Loading {group.name}…</p>
  return <Competition accountId={profile.id} detail={details}
    initialChallengeId={initialChallengeId} expanded />
}

function Guide({ navigate }: { navigate: (view: View) => void }) {
  return <>
    <h1>Help</h1>
    <ul className="group-list">
      <li><span><strong>Connect a practice profile</strong><small>Manage your LeetCode handle and sync status.</small></span>
        <button type="button" className="text-button" onClick={() => navigate('profile')}>Go to Profile</button></li>
      <li><span><strong>Join or create a group</strong><small>Open a private invitation or find a public group.</small></span>
        <button type="button" className="text-button" onClick={() => navigate('groups')}>Go to My groups</button></li>
      <li><span><strong>Review recorded progress</strong><small>See topics, XP, and streaks in Analytics.</small></span>
        <button type="button" className="text-button" onClick={() => navigate('analytics')}>Go to Analytics</button></li>
    </ul>
  </>
}

export function Workspace({ profile, profilePanel, settingsPanel, notice, clearNotice, initialView = 'overview' }: {
  profile: Profile
  initialView?: View
  profilePanel: ReactNode
  settingsPanel: ReactNode
  notice: ReactNode
  clearNotice: () => void
}) {
  const [view, setView] = useState<View>(initialView)
  const [directory, setDirectory] = useState<Directory | null>(null)
  const [activeId, setActiveId] = useState<string | null>(null)
  const [groupId, setGroupId] = useState<string | null>(null)
  const [groupChallengeId, setGroupChallengeId] = useState<string | null>(null)
  const [query, setQuery] = useState('')
  const [accessError, setAccessError] = useState(false)
  const [rankingScope, setRankingScope] = useState<'group' | 'individual'>('group')
  const searchDialog = useRef<HTMLDialogElement>(null)
  const menu = useRef<HTMLDialogElement>(null)
  const group = directory?.items.find(item => item.id === activeId) ?? directory?.items[0]
  const rankingGroup = rankingScope === 'group' && !accessError ? group : undefined

  useEffect(() => {
    let keep = true
    groupsApi.list(profile.id).then(data => { if (keep) setDirectory(data) }).catch(() => undefined)
    return () => { keep = false }
  }, [profile.id, view, groupId])

  useEffect(() => {
    function keyboard(event: KeyboardEvent) {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k' && !menu.current?.open) {
        event.preventDefault()
        setQuery('')
        if (!searchDialog.current?.open) searchDialog.current?.showModal()
      }
    }
    window.addEventListener('keydown', keyboard)
    return () => window.removeEventListener('keydown', keyboard)
  }, [])

  function navigate(next: View) {
    setView(next)
    setAccessError(false)
    clearNotice()
    menu.current?.close()
    searchDialog.current?.close()
    window.scrollTo({ top: 0 })
  }

  function chooseGroup(id: string | null) {
    setGroupId(id)
    setGroupChallengeId(null)
    if (id) setActiveId(id)
  }

  function showGroup(id: string, challengeId: string | null = null) {
    navigate('groups')
    chooseGroup(id)
    setGroupChallengeId(challengeId)
  }

  function showGroupList() {
    navigate('groups')
    chooseGroup(null)
  }

  function showLeaderboard() {
    setRankingScope('group')
    navigate('leaderboard')
  }

  function navClick(next: View) {
    if (next === 'groups' && view === 'groups') showGroupList()
    else navigate(next)
  }

  const searchPages = [...navigation, ...accountPages, help, { id: 'notifications' as const, label: 'Notifications', icon: 'notifications' }]
    .filter(item => item.label.toLowerCase().includes(query.trim().toLowerCase()))
  const searchGroups = query.trim() ? (directory?.items ?? []).filter(item => item.name.toLowerCase().includes(query.trim().toLowerCase())) : []

  const navProps = {
    view,
    displayName: profile.displayName,
    onNavigate: navClick,
  }
  return <div className="orbital-app">
    <RewardToasts key={profile.id} accountId={profile.id} />
    <a href="#workspace-main" className="skip-link">Skip to content</a>
    <aside className="orbital-sidebar"><WorkspaceNav {...navProps} /></aside>
    <div className="orbital-workspace">
      <header className="orbital-topbar">
        <div className="topbar-start">
          <button type="button" className="icon-button mobile-menu" aria-label="Open navigation" onClick={() => menu.current?.showModal()}>
            <Icon name="menu" /></button>
          {group ? <label className="topbar-group-switch">
            <span className="topbar-group-label">Group</span>
            <span className="topbar-group topbar-group-picker">
              <span className="signal-dot" aria-hidden="true" />
              <select value={group.id} onChange={event => {
                const id = event.target.value
                setActiveId(id)
                if (view === 'groups') chooseGroup(id)
                setAccessError(false)
              }}>
                {directory?.items.map(item => <option value={item.id} key={item.id}>{item.name}</option>)}
              </select>
            </span>
          </label> : <button type="button" className="topbar-group" onClick={showGroupList}>
            <span className="signal-dot" aria-hidden="true" />Personal workspace</button>}
          <button type="button" className="topbar-docs" onClick={() => navigate('help')}>Docs</button>
        </div>
        <div className="topbar-actions">
          <button type="button" className="topbar-search" aria-label="Search workspace" onClick={() => {
            setQuery('')
            searchDialog.current?.showModal()
          }}><Icon name="search" /><span>Search workspace…</span><kbd>⌘K</kbd></button>
          <button type="button" className="icon-button" aria-label="Notifications" onClick={() => navigate('notifications')}>
            <Icon name="notifications" /></button>
        </div>
      </header>

      <main id="workspace-main" tabIndex={-1} className="orbital-main">
        {view === 'overview' ? <Dashboard key={`${profile.id}:${group?.id ?? ''}`} profile={profile} group={group}
          onLeaderboard={showLeaderboard}
          onPractice={() => navigate('practice')} onProfile={() => navigate('profile')}
          onQuests={() => navigate('quests')} onAnalytics={() => navigate('analytics')} /> :
          <div className={`workspace-page page-${view}${view === 'quests' || view === 'achievements' || view === 'leaderboard' ? '' : ' deck-panel'}`}>
            {view === 'groups' && <Groups accountId={profile.id} selectedId={groupId} challengeId={groupChallengeId}
              onSelect={chooseGroup} onPractice={() => navigate('practice')} />}
            {view === 'practice' && <Practice accountId={profile.id} />}
            {view === 'analytics' && <Analytics accountId={profile.id} />}
            {view === 'notifications' && <Inbox accountId={profile.id} onOpen={(id, challengeId) => showGroup(id, challengeId)} />}
            {view === 'profile' && profilePanel}
            {view === 'settings' && settingsPanel}
            {view === 'help' && <Guide navigate={navigate} />}
            {view === 'leaderboard' && <>
              <div className="ranking-periods ranking-scope" role="group" aria-label="Leaderboard scope">
                <button type="button" aria-pressed={Boolean(rankingGroup)} disabled={!group || accessError}
                  onClick={() => setRankingScope('group')}>Group</button>
                <button type="button" aria-pressed={!rankingGroup}
                  onClick={() => setRankingScope('individual')}>Individual</button>
              </div>
              <GroupStandings key={rankingGroup?.id ?? 'individual'} accountId={profile.id}
                id={rankingGroup?.id ?? null} groupName={rankingGroup?.name}
                onAccessLost={() => setAccessError(true)} />
            </>}
            {view === 'challenges' && <>
              <h1>Duels</h1>
              {group ? <ChallengePage key={group.id} profile={profile} group={group}
                initialChallengeId={null} /> :
                <GroupEmpty onGroups={showGroupList}>Join a group to invite a duel opponent.</GroupEmpty>}
            </>}
            {(view === 'quests' || view === 'achievements') &&
              <Rewards key={view === 'quests' ? `quests:${group?.id ?? ''}` : view} accountId={profile.id} section={view} group={group}
                onPractice={() => navigate('practice')} onAchievements={() => navigate('achievements')} />}
          </div>}
        {notice}
      </main>
      <footer className="orbital-footer"><span>LeetGrind Observatory</span><span>All times UTC</span></footer>
    </div>
    <dialog ref={menu} className="orbital-menu" aria-label="Workspace navigation">
      <button type="button" className="icon-button close-menu" aria-label="Close navigation" onClick={() => menu.current?.close()}><Icon name="close" /></button>
      <WorkspaceNav {...navProps} />
    </dialog>
    <dialog ref={searchDialog} className="orbital-dialog" aria-labelledby="search-heading">
      <header className="deck-header"><h2 id="search-heading">Search workspace</h2>
        <button type="button" className="icon-button" aria-label="Close search" onClick={() => searchDialog.current?.close()}><Icon name="close" /></button>
      </header>
      <label className="visually-hidden" htmlFor="workspace-search">Search pages or groups</label>
      <input id="workspace-search" type="search" placeholder="Search pages or groups" autoFocus value={query} onChange={event => setQuery(event.target.value)} />
      <ul className="search-results">
        {searchPages.map(item => <li key={item.id}><button type="button" onClick={() => navigate(item.id)}><Icon name={item.icon} />{item.label}</button></li>)}
        {searchGroups.map(item => <li key={item.id}><button type="button" onClick={() => showGroup(item.id)}><Icon name="group" />{item.name}</button></li>)}
      </ul>
      {!searchPages.length && !searchGroups.length && <p className="muted" role="status">No matching pages or groups.</p>}
    </dialog>
  </div>
}
