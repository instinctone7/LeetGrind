import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function signIn(page: Page, name: string) {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  const id = randomUUID().replaceAll('-', '').slice(0, 10)
  const email = 'group-' + id + '@example.test'
  await page.route('**/api/v1/me/leetcode-link/preview*', route => route.fulfill({ json: null }))
  const password = 'phase-three-only-password'
  const response = await page.request.post('/api/v1/auth/register', {
    headers: { Origin: new URL(page.url()).origin, 'X-LeetGrind-Client': 'browser' },
    data: { email, leetcodeHandle: 'group_' + id, displayName: name, password },
  })
  expect(response.status()).toBe(201)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.getByRole('button', { name: 'My groups', exact: true }).click()
  return (await response.json()).handle as string
}

test('two accounts share private standings and access ends on removal', async ({ browser }) => {
  test.setTimeout(60_000)
  const owner = await browser.newPage()
  const learner = await browser.newPage()
  try {
    await signIn(owner, 'Group Owner')
    await owner.getByRole('button', { name: 'Create a group', exact: true }).click()
    await owner.getByLabel('Group name').fill('Interview study group')
    await owner.getByRole('button', { name: 'Create group', exact: true }).click()
    await expect(owner.getByRole('heading', { name: 'Interview study group' })).toBeVisible()
    await owner.getByRole('button', { name: 'Create invitation', exact: true }).click()
    const invitation = owner.getByRole('textbox', { name: 'New invitation code' })
    await expect(invitation).toHaveValue(/^[A-Za-z0-9_-]{43}$/)
    const code = await invitation.inputValue()
    await expect(owner.getByLabel('Standings view')).toHaveCount(0)

    const handle = await signIn(learner, 'Group Learner')
    await learner.getByRole('button', { name: 'Join with invite code', exact: true }).click()
    await learner.getByLabel('Invitation code', { exact: true }).fill(code)
    await learner.getByRole('dialog', { name: 'Join with invite code' }).getByRole('button', { name: 'Join group', exact: true }).click()
    await expect(learner.getByRole('heading', { name: 'Interview study group' })).toBeVisible()

    const ownerScore = owner.getByRole('row').filter({ hasText: '@' + handle })
    await expect(ownerScore).toContainText('0 XP', { timeout: 15000 })
    await expect(owner.getByRole('heading', { name: 'Squadron Roster' })).toBeVisible()

    await learner.getByRole('button', { name: 'Overview', exact: true }).click()
    await expect(learner.getByRole('heading', { name: 'Welcome back, Group Learner.' })).toBeVisible()
    const groupLabel = learner.getByRole('banner').getByText('Group', { exact: true })
    const groupPicker = learner.getByRole('combobox', { name: 'Group', exact: true })
    await expect(groupLabel).toBeVisible()
    await expect(groupPicker).toHaveValue(/^[0-9]+$/)
    const desktop = learner.viewportSize()!
    await learner.setViewportSize({ width: 320, height: 720 })
    await expect(groupLabel).toBeInViewport({ ratio: 1 })
    await expect(groupPicker).toBeInViewport({ ratio: 1 })
    await learner.setViewportSize(desktop)
    await expect(learner.getByLabel('Workspace data')).toHaveCount(0)
    await expect(learner.getByRole('region', { name: 'Weekly leaderboard' })).toContainText('0 XP')

    await learner.getByRole('button', { name: 'Quests', exact: true }).click()
    const highlight = learner.getByRole('region', { name: 'Daily highlight' })
    await expect(highlight).toContainText('0 of 2 group members solved')
    await expect(highlight.getByRole('link', { name: 'Solve on LeetCode' })).toHaveAttribute('href', /^https:\/\/leetcode\.com\/problems\//)
    const directives = learner.getByRole('region', { name: 'Directives' })
    await expect(directives.getByText('Solve 3 distinct new problems')).toBeVisible()
    await directives.getByText('Eligible problems', { exact: true }).click()
    await expect(directives.getByRole('link', { name: 'two sum' })).toBeVisible()
    await expect(learner.getByRole('region', { name: 'Streak milestones' }).getByRole('progressbar')).toHaveAttribute('max', '3')
    await learner.setViewportSize({ width: 320, height: 812 })
    expect(await learner.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
    await learner.setViewportSize(desktop)
    await directives.getByRole('button', { name: 'View practice' }).click()
    await expect(learner.getByRole('heading', { name: 'Practice & Mastery' })).toBeVisible()
    await learner.getByRole('button', { name: 'Achievements', exact: true }).click()
    await expect(learner.getByRole('region', { name: 'Achievement summary' })).toContainText('0 / 2')
    const badges = learner.getByRole('region', { name: 'Badges', exact: true })
    await expect(badges.getByRole('heading', { name: 'First solve' })).toBeVisible()
    await badges.getByRole('button', { name: 'Unlocked (0)', exact: true }).click()
    await expect(badges).toContainText('No achievements unlocked yet.')
    await badges.getByRole('button', { name: 'All (2)', exact: true }).click()
    await expect(badges.getByRole('progressbar', { name: 'Three-day streak progress' })).toHaveAttribute('max', '3')
    await learner.setViewportSize({ width: 320, height: 812 })
    expect(await learner.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
    await learner.setViewportSize(desktop)
    await learner.getByRole('button', { name: 'Overview', exact: true }).click()

    await learner.keyboard.press('Control+k')
    await learner.getByRole('searchbox', { name: 'Search pages or groups' }).fill('practice')
    await learner.getByRole('dialog', { name: 'Search workspace' }).getByRole('button', { name: 'Practice', exact: true }).click()

    await learner.getByLabel('Filter problems').fill('two sum')
    await expect(learner.getByRole('link', { name: 'Solve Two Sum', exact: true })).toBeVisible()
    await expect(learner.getByRole('button', { name: /record solve/i })).toHaveCount(0)
    await learner.getByRole('button', { name: 'Overview', exact: true }).click()
    const weekly = learner.getByRole('region', { name: 'Weekly leaderboard' })
    await expect(weekly).toContainText('0 XP')
    await weekly.getByRole('button', { name: 'View all' }).click()
    await expect(learner.getByRole('heading', { name: 'Group Rankings' })).toBeVisible()
    const podium = learner.getByRole('region', { name: 'Podium' })
    await expect(podium.getByRole('listitem').filter({ hasText: 'Group Learner' })).toBeVisible()
    const standings = learner.getByRole('table')
    await expect(standings.getByRole('row', { name: /Group Learner/ })).toContainText('0 XP')
    await learner.getByRole('searchbox', { name: 'Filter loaded members' }).fill('Group Learner')
    await expect(standings.getByRole('row', { name: /Group Owner/ })).toHaveCount(0)
    await learner.getByRole('button', { name: 'Monthly', exact: true }).click()
    await expect(learner.getByRole('heading', { name: 'Monthly standings' })).toBeVisible()
    await expect(standings.getByRole('row', { name: /Group Learner/ })).toContainText('0 XP')
    await learner.setViewportSize({ width: 320, height: 812 })
    await expect(podium).toBeInViewport()
    expect(await learner.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
    await learner.setViewportSize(desktop)
    const scope = learner.getByRole('group', { name: 'Leaderboard scope' })
    await scope.getByRole('button', { name: 'Individual', exact: true }).click()
    await expect(learner.getByRole('heading', { name: 'Individual Rankings' })).toBeVisible()
    await expect(standings.getByRole('row', { name: /Group Learner/ })).toContainText('5 XP')
    await verifyExpandedStandingsRefresh(learner)
    await scope.getByRole('button', { name: 'Group', exact: true }).click()
    await expect(standings.getByRole('row', { name: /Group Learner/ })).toContainText('0 XP')

    await learner.reload()
    await learner.getByRole('button', { name: 'My groups', exact: true }).click()
    const privateGroup = learner.getByRole('button', { name: 'Interview study group', exact: true })
    const groupCard = learner.getByRole('article').filter({ has: privateGroup })
    await expect(groupCard).toContainText('2 members')
    await learner.getByRole('button', { name: 'Public (0)', exact: true }).click()
    await expect(privateGroup).toHaveCount(0)
    await learner.getByRole('button', { name: 'Private (1)', exact: true }).click()
    const groupSearch = learner.getByRole('searchbox', { name: 'Filter groups' })
    await groupSearch.fill('No matching group')
    await expect(privateGroup).toHaveCount(0)
    await groupSearch.fill('Interview')
    await expect(privateGroup).toBeVisible()
    await learner.setViewportSize({ width: 320, height: 812 })
    expect(await learner.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(320)
    await learner.setViewportSize(desktop)
    await privateGroup.click()
    await expect(learner.getByRole('row').filter({ hasText: '@' + handle })).toContainText('0 XP')

    await owner.getByRole('button', { name: 'Back to groups', exact: true }).click()
    await owner.getByRole('button', { name: 'Interview study group', exact: true }).click()
    await owner.getByText('Members (2)', { exact: true }).click()
    await owner.getByRole('button', { name: 'Remove Group Learner', exact: true }).click()
    await expect(learner.getByRole('alert')).toContainText('This group is unavailable.', { timeout: 15000 })
    await expect(owner.getByRole('button', { name: 'New mission', exact: true, includeHidden: true })).toHaveCount(0)
    await owner.getByRole('navigation', { name: 'Workspace', exact: true }).getByRole('button', { name: 'Challenges' }).click()
    await expect(owner.getByRole('heading', { name: 'Challenges', exact: true })).toBeVisible()
    await owner.getByText('Create a challenge or duel', { exact: true }).click()
    await expect(owner.getByLabel('Challenge title')).toBeVisible()
  } finally {
    await Promise.all([owner.close(), learner.close()])
  }
})

async function verifyExpandedStandingsRefresh(page: Page) {
  let peerXp = 0
  const individualApi = (url: URL) => url.pathname === '/api/v1/leaderboard'
  await page.route(individualApi, route => {
    const expanded = new URL(route.request().url()).searchParams.get('limit') === '40'
    const learner = { id: '1', displayName: 'Group Learner', handle: 'learner', rank: peerXp > 5 ? 2 : 1, xp: 5 }
    const peer = { id: '2', displayName: 'Second Learner', handle: 'second', rank: peerXp > 5 ? 1 : 2, xp: peerXp }
    return route.fulfill({ json: {
      period: 'weekly', mode: 'live', timezone: 'UTC',
      periodStart: '2026-11-23T00:00:00Z', periodEnd: '2026-11-30T00:00:00Z',
      items: expanded ? (peer.rank === 1 ? [peer, learner] : [learner, peer]) : [learner],
      nextCursor: expanded ? null : 'next',
    } })
  })
  await page.getByRole('button', { name: 'Refresh standings', exact: true }).click()
  await page.getByRole('button', { name: 'Load more', exact: true }).click()
  const rows = page.getByRole('table').getByRole('row')
  await expect(rows.nth(2)).toContainText('Second Learner')
  await expect(rows.nth(2)).toContainText('0 XP')
  peerXp = 25
  await expect(rows.nth(1)).toContainText('Second Learner', { timeout: 15_000 })
  await expect(rows.nth(1)).toContainText('25 XP')
  await page.unroute(individualApi)
}

test('public discovery, period views, and a duel through its invitation', async ({ browser }) => {
  test.setTimeout(60_000)
  const owner = await browser.newPage()
  const learner = await browser.newPage()
  const name = 'Open Practice ' + randomUUID().slice(0, 8)
  try {
    const [ownerHandle] = await Promise.all([signIn(owner, 'Duel Host'), signIn(learner, 'Duel Learner')])
    await owner.getByRole('button', { name: 'Create a group', exact: true }).click()
    await owner.getByLabel('Group name').fill(name)
    await owner.getByLabel('Description').fill('Practice together before interviews.')
    await owner.getByLabel('Visibility').selectOption('PUBLIC')
    await owner.getByRole('button', { name: 'Create group', exact: true }).click()
    await expect(owner.getByRole('heading', { name, exact: true })).toBeVisible()

    await learner.getByRole('button', { name: 'Account menu' }).click()
    await learner.getByRole('button', { name: 'Profile', exact: true }).click()
    await learner.getByRole('button', { name: 'My groups', exact: true }).click()
    await learner.getByRole('button', { name: 'Find public groups', exact: true }).click()
    await learner.getByRole('button', { name: 'Join ' + name }).click()
    await expect(learner.getByRole('heading', { name, exact: true })).toBeVisible()
    await learner.getByLabel('Standings period').selectOption('monthly')
    await expect(learner.getByRole('heading', { name: 'Monthly standings' })).toBeVisible()
    await expect(learner.getByRole('row').filter({ hasText: '@' + ownerHandle })).toContainText('0 XP')
    await learner.getByLabel('Standings period').selectOption('all-time')
    await expect(learner.getByRole('heading', { name: 'All-time standings' })).toBeVisible()
    await expect(learner.getByText('All eligible membership periods')).toBeVisible()

    await owner.getByRole('button', { name: 'Back to groups' }).click()
    await owner.getByRole('button', { name, exact: true }).click()
    await expect(owner.getByLabel('Competition view')).toHaveCount(0)
    await owner.getByText('Challenges and duels', { exact: true }).click()
    await owner.getByText('Create a challenge or duel', { exact: true }).click()
    await owner.getByLabel('Competition type').selectOption('DUEL')
    await owner.getByLabel('Opponent').selectOption({ label: 'Duel Learner' })
    await owner.getByLabel('Challenge title').fill('Array Duel')
    await owner.getByLabel('Scoring rule').selectOption('PROBLEM')
    await owner.getByLabel('Problem slug').fill('two-sum')
    await owner.getByLabel('Deadline (UTC)').fill(new Date(Date.now() + 60 * 60 * 1000).toISOString().slice(0, 16))
    await owner.getByRole('button', { name: 'Publish duel' }).click()
    await expect(owner.getByRole('heading', { name: 'Array Duel' })).toBeVisible()
    await expect(owner.getByText('Duel · Awaiting opponent')).toBeVisible()

    await learner.getByRole('button', { name: 'Notifications', exact: true }).click()
    await learner.getByRole('button', { name: 'Open Invitation to Array Duel' }).click()
    await learner.getByRole('button', { name: 'Accept duel' }).click()
    await expect(learner.getByText('Duel · Active', { exact: true })).toBeVisible()
  } finally {
    await Promise.all([owner.close(), learner.close()])
  }
})
