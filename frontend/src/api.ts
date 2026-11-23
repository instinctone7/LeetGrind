export type Profile = {
  id: string
  email: string
  handle: string
  displayName: string
}

type Access = { accountId: string; accessToken: string; expiresIn: number }
type Login = { email: string; password: string }
type Registration = Login & { displayName: string; leetcodeHandle: string }

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
    public readonly correlationId?: string,
  ) {
    super(message)
  }
}

let access: Access | null = null
let refreshInFlight: Promise<Access> | null = null

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const response = await fetch('/api/v1' + path, { ...init, credentials: 'same-origin' })
  if (!response.ok) {
    const error = await response.json().catch(() => null)
    throw new ApiError(
      response.status,
      error?.message ?? 'The request failed. Try again.',
      error?.correlationId,
    )
  }
  if (response.status === 204) return undefined as T
  return response.json()
}

async function withCookieLock<T>(callback: () => Promise<T>): Promise<T> {
  return navigator.locks ? navigator.locks.request('leetgrind-browser-session', callback) : callback()
}

function authPost<T>(path: string, body?: unknown) {
  const headers = new Headers({ 'X-LeetGrind-Client': 'browser' })
  if (body !== undefined) headers.set('Content-Type', 'application/json')
  return withCookieLock(() => request<T>('/auth/' + path, {
    method: 'POST',
    headers,
    body: body === undefined ? undefined : JSON.stringify(body),
  }))
}

function refresh() {
  if (!refreshInFlight) {
    refreshInFlight = authPost<Access>('refresh')
      .then(result => { access = result; return result })
      .catch(error => { access = null; throw error })
      .finally(() => { refreshInFlight = null })
  }
  return refreshInFlight
}

async function protectedRequest<T>(path: string, init: RequestInit = {}, expectedAccount?: string): Promise<T> {
  const original = access ?? await refresh()
  const accountId = expectedAccount ?? original.accountId
  function authorizedHeaders(session: Access) {
    if (accountId !== session.accountId) {
      throw new ApiError(401, 'The browser account changed. Sign in again.')
    }
    const headers = new Headers(init.headers)
    headers.set('Authorization', 'Bearer ' + session.accessToken)
    return headers
  }

  const headers = authorizedHeaders(original)
  try {
    return await request<T>(path, { ...init, headers })
  } catch (error) {
    if (!(error instanceof ApiError) || error.status !== 401) throw error
    const replacement = access && original !== access ? access : await refresh()
    return request<T>(path, { ...init, headers: authorizedHeaders(replacement) })
  }
}

export const accountApi = {
  restore: () => protectedRequest<Profile>('/me'),
  register: (input: Registration) => authPost<Profile>('register', input),
  async login(input: Login) {
    access = await authPost<Access>('login', input)
    return protectedRequest<Profile>('/me')
  },
  updateProfile: (accountId: string, displayName: string) => protectedRequest<Profile>('/me', {
    method: 'PATCH',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ displayName }),
  }, accountId),
  async deleteAccount(accountId: string, password: string) {
    await protectedRequest<void>('/me/delete', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ password }),
    }, accountId)
    access = null
  },
  async logout() {
    await authPost<void>('logout')
    access = null
  },
}

export type LinkStatus = {
  state: 'NONE' | 'PENDING' | 'VERIFIED' | 'DISCONNECTED'
  handle: string | null
  fixture: boolean
  demoAvailable: boolean
  previewEnabled: boolean
  syncAvailable: boolean
  providerPaused: boolean
  syncState: 'QUEUED' | 'RUNNING' | 'RETRY' | 'PAUSED' | 'IDLE' | null
  failureCode: string | null
  coverageUncertain: boolean
  activatedAt: string | null
  lastAttemptAt: string | null
  lastSuccessfulSyncAt: string | null
  nextSyncAt: string | null
  manualSyncAvailableAt: string | null
}

export type Progress = { totalXp: number; solvedCount: number; fixtureXp: number; fixtureSolvedCount: number }
export type CatalogProblem = { slug: string; title: string; difficulty: Difficulty; solved: boolean; topics: string[] }
export type XpEntry = {
  id: string
  provider: 'FIXTURE' | 'LEETCODE' | 'PERSONAL'
  reason: string
  problemTitle: string | null
  difficulty: 'EASY' | 'MEDIUM' | 'HARD' | null
  amount: number
  effectiveAt: string
}
type History = { items: XpEntry[]; nextCursor: string | null }
export type RewardEvent = {
  id: string
  kind: 'DAILY_LOGIN' | 'BASE_SOLVE' | 'ACHIEVEMENT'
  label: string | null
  amount: number | null
  occurredAt: string
}

export type LeetCodePreview = {
  username: string
  realName: string | null
  ranking: number | null
  problemsSolved: { all: number; easy: number; medium: number; hard: number }
  heatmap: { streak: number; totalActiveDays: number; activityDays: { date: string; submissions: number }[] }
  recentSubmissions: {
    title: string
    titleSlug: string
    timestamp: number
    lang: string | null
    difficulty?: 'EASY' | 'MEDIUM' | 'HARD' | null
  }[]
}

export const practiceApi = {
  catalog: (accountId: string, mode: StandingsMode) =>
    protectedRequest<CatalogProblem[]>('/me/practice-catalog?mode=' + mode, {}, accountId),
  preview: async (accountId: string, handle?: string) => (await protectedRequest<LeetCodePreview | null>(
    '/me/leetcode-link/preview' + (handle ? '?' + new URLSearchParams({ handle }) : ''), {}, accountId)) ?? null,
  status: (accountId: string) => protectedRequest<LinkStatus>('/me/leetcode-link', {}, accountId),
  link: (accountId: string, handle: string) => protectedRequest<LinkStatus>('/me/leetcode-link', {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ handle }),
  }, accountId),
  disconnect: (accountId: string) =>
    protectedRequest<void>('/me/leetcode-link', { method: 'DELETE' }, accountId),
  sync: (accountId: string) =>
    protectedRequest<LinkStatus>('/me/leetcode-link/sync', { method: 'POST' }, accountId),
  progress: (accountId: string) => protectedRequest<Progress>('/me/progress', {}, accountId),
  checkIn: (accountId: string) =>
    protectedRequest<{ nextCheckInAt: string }>('/me/check-in', { method: 'POST' }, accountId),
  rewardEvents: (accountId: string) => protectedRequest<RewardEvent[]>('/me/reward-events', {}, accountId),
  history: (accountId: string, cursor: string | null = null) =>
    protectedRequest<History>('/me/xp-entries' + (cursor ? '?cursor=' + encodeURIComponent(cursor) : ''), {}, accountId),
}

export type PublicGroup = { id: string; name: string; description: string }
export type Group = PublicGroup & { role: 'OWNER' | 'MEMBER'; visibility: 'PRIVATE' | 'PUBLIC' }
export type GroupMetrics = {
  id: string; weeklyXp: number; weeklySolves: number; personalXp: number; memberCount: number; memberInitials: string; lastSolveAt: string | null
}
export type GroupMember = { id: string; handle: string; displayName: string; role: Group['role'] }
export type GroupInvite = { id: string; expiresAt: string; revokedAt: string | null; maxUses: number; uses: number }
type GroupDetails = { group: Group; members: GroupMember[] }
type NewInvite = { id: string; code: string; expiresAt: string; maxUses: number }
export type StandingsMode = 'live' | 'demo'
export type StandingsPeriod = 'weekly' | 'monthly' | 'all-time'
export type Standings = {
  period: StandingsPeriod
  mode: StandingsMode
  periodStart: string | null
  periodEnd: string | null
  timezone: 'UTC'
  generatedAt: string
  items: { id: string; handle: string; displayName: string; rank: number; xp: number }[]
  nextCursor: string | null
}

function groupRequest<T>(accountId: string, path: string, method = 'GET', body?: unknown) {
  return protectedRequest<T>(path, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  }, accountId)
}

export const groupsApi = {
  list: (accountId: string) => groupRequest<{ items: Group[]; demoAvailable: boolean }>(accountId, '/groups'),
  overview: (accountId: string) => groupRequest<GroupMetrics[]>(accountId, '/groups/overview'),
  details: (accountId: string, id: string) => groupRequest<GroupDetails>(accountId, '/groups/' + id),
  create: (accountId: string, settings: { name: string; description: string; visibility: Group['visibility'] }) =>
    groupRequest<Group>(accountId, '/groups', 'POST', settings),
  update: (accountId: string, id: string, settings: { name: string; description: string }) =>
    groupRequest<Group>(accountId, '/groups/' + id, 'PATCH', settings),
  join: (accountId: string, code: string) =>
    groupRequest<Group>(accountId, '/group-memberships', 'POST', { code }),
  discover: (accountId: string, before?: string) =>
    groupRequest<PublicGroup[]>(accountId, '/groups/public' + beforeParam(before)),
  joinPublic: (accountId: string, groupId: string) =>
    groupRequest<Group>(accountId, '/groups/' + groupId + '/join', 'POST'),
  remove: (accountId: string, id: string, member: string) =>
    groupRequest<void>(accountId, '/groups/' + id + '/members/' + member, 'DELETE'),
  close: (accountId: string, id: string) => groupRequest<void>(accountId, '/groups/' + id, 'DELETE'),
  transfer: (accountId: string, id: string, newOwner: string) =>
    groupRequest<void>(accountId, '/groups/' + id + '/ownership', 'POST', { accountId: newOwner }),
  invites: (accountId: string, id: string) => groupRequest<GroupInvite[]>(accountId, '/groups/' + id + '/invites'),
  invite: (accountId: string, id: string, settings: { expiresInHours: number; maxUses: number }) =>
    groupRequest<NewInvite>(accountId, '/groups/' + id + '/invites', 'POST', settings),
  revoke: (accountId: string, id: string, inviteId: string) =>
    groupRequest<void>(accountId, '/groups/' + id + '/invites/' + inviteId, 'DELETE'),
  standings(accountId: string, id: string | null, mode: StandingsMode, period: StandingsPeriod, date: string, cursor?: string, limit = 20) {
    const query = new URLSearchParams({ period, mode })
    if (limit !== 20) query.set('limit', String(limit))
    if (date && period !== 'all-time') query.set(period === 'weekly' ? 'week' : 'month', date)
    if (cursor) query.set('cursor', cursor)
    const path = id ? '/groups/' + id + '/leaderboard' : '/leaderboard'
    return groupRequest<Standings>(accountId, path + '?' + query)
  },
}

function beforeParam(before?: string) {
  return before ? '?before=' + encodeURIComponent(before) : ''
}

export type ChallengeRule = 'PROBLEM' | 'COUNT' | 'DIFFICULTY'
export type ChallengeKind = 'GROUP' | 'DUEL'
export type Difficulty = NonNullable<XpEntry['difficulty']>
export type DuelDraft = { inviteeId: string; problemUrl: string; durationMinutes: number; stakeXp: number }
export type DuelSummary = { availableXp: number; wins: number; losses: number; netXp: number }
export type Challenge = {
  id: string; title: string; kind: ChallengeKind; rule: ChallengeRule; mode: StandingsMode
  problemSlug: string | null; difficulty: Difficulty | null; target: number
  startsAt: string; endsAt: string; cutoffAt: string; ruleVersion: number; enrolled: number
  status: 'SCHEDULED' | 'AWAITING_OPPONENT' | 'ACTIVE' | 'GRACE' | 'FINALIZED'
  finalizedAt: string | null; outcome: 'WIN' | 'TIE' | 'NO_SCORE' | 'CANCELLED' | null
  durationMinutes: number | null; stakeXp: number | null; problemUrl: string | null
  xpDelta: number | null; opponent: string | null
}
export type ChallengeView = {
  challenge: Challenge; canEnroll: boolean
  participants: { id: string; handle: string; displayName: string; enrolledAt: string | null; score: number; winner: boolean; xpDelta: number | null }[]
}
export type Notification = {
  id: string; kind: 'INVITATION' | 'CHALLENGE_RESULT' | 'ACHIEVEMENT' | 'STANDINGS_CHANGE'
  label: string | null; groupId: string | null; groupName: string | null
  challengeId: string | null; challengeTitle: string | null; createdAt: string; readAt: string | null
}

export const competitionApi = {
  list: (account: string, group: string, mode: StandingsMode, before?: string) =>
    groupRequest<Challenge[]>(account, '/groups/' + group + '/challenges?' + new URLSearchParams({ mode, ...(before && { before }) })),
  detail: (account: string, group: string, id: string) =>
    groupRequest<ChallengeView>(account, '/groups/' + group + '/challenges/' + id),
  createDuel: (account: string, group: string, draft: DuelDraft) =>
    groupRequest<ChallengeView>(account, '/groups/' + group + '/duels', 'POST', draft),
  duelSummary: (account: string, group: string) =>
    groupRequest<DuelSummary>(account, '/groups/' + group + '/duels/summary'),
  enroll: (account: string, group: string, id: string) =>
    groupRequest<ChallengeView>(account, '/groups/' + group + '/challenges/' + id + '/enrollment', 'POST'),
  inbox: (account: string, before?: string) =>
    groupRequest<Notification[]>(account, '/me/notifications' + beforeParam(before)),
  read: (account: string, id: string) => groupRequest<void>(account, '/me/notifications/' + id + '/read', 'POST'),
}

type RewardTask = {
  kind: 'FEATURED' | 'DAILY_QUEST' | 'WEEKLY_QUEST'
  startsAt: string
  endsAt: string
  cutoffAt: string
  ruleVersion: number
  rewardXp: number
  target: number
  completed: number
  topic: string | null
  eligibleProblemSlugs: string[]
}

export type RewardsProgress = {
  mode: StandingsMode
  totalXp: number
  ledgerRevision: string | null
  level: { number: number; minimumXp: number; nextXp: number | null; ruleVersion: number }
  activity: { currentStreak: number; longestStreak: number; calendarStart: string; calendarEnd: string; codingDates: string[] }
  loginStreak: { currentStreak: number; longestStreak: number }
  recentSolves: { date: string; slug: string; title: string; provider: 'FIXTURE' | 'LEETCODE' }[]
  featured: { title: string; difficulty: string; topics: string[]; url: string | null; task: RewardTask }
  quests: RewardTask[]
  badges: { code: string; ruleVersion: number; unlockedAt: string }[]
}

export type GroupFeatured = {
  title: string
  startsAt: string
  endsAt: string
  cutoffAt: string
  members: number
  completed: number
}

export const rewardsApi = {
  progress: (accountId: string, mode: StandingsMode) =>
    protectedRequest<RewardsProgress>('/me/rewards?mode=' + mode, {}, accountId),
  groupFeatured: (accountId: string, groupId: string, mode: StandingsMode) =>
    groupRequest<GroupFeatured>(accountId, '/groups/' + groupId + '/featured-problem?mode=' + mode),
}

export type AnalyticsPeriod = 'all-time' | 'weekly' | 'monthly'
export type AnalyticsBucket = {
  start: string
  end: string
  solved: number
  codingDays: number
  xpEarned: number
  totalXp: number
}
export type AnalyticsView = {
  mode: StandingsMode
  demoAvailable: boolean
  timezone: 'UTC'
  generatedAt: string
  coverage: {
    state: 'UNAVAILABLE' | 'NO_LINK' | 'AWAITING_SYNC' | 'PARTIAL' | 'TRACKED_ONLY' | 'DISCONNECTED'
    trackedSince: string | null
    lastSuccessfulSyncAt: string | null
    historicalImportAvailable: boolean
  }
  summary: {
    period: AnalyticsPeriod
    start: string | null
    end: string | null
    solved: number
    unmapped: number
    xpEarned: number
    codingDays: number
    byDifficulty: { difficulty: Difficulty; solved: number; unmapped: number }[]
    topics: { topic: string; solved: number }[]
  }
  streaks: { current: number; longest: number }
  dailyActivity: { date: string; solves: number }[]
  weekly: AnalyticsBucket[]
  monthly: AnalyticsBucket[]
  recommendations: {
    id: string; slug: string; title: string; difficulty: Difficulty; topics: string[]; focusTopic: string
    recordedInFocusTopic: number; reason: string; url: string | null
  }[]
}

export const analyticsApi = {
  view(accountId: string, mode: StandingsMode, period: AnalyticsPeriod, start: string) {
    const search = new URLSearchParams({ mode, period })
    if (start) search.set('start', start)
    return protectedRequest<AnalyticsView>('/me/analytics?' + search, {}, accountId)
  },
}
