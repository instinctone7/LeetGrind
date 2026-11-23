import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function openWorkspace(page: Page, name: string) {
  const menu = page.getByRole('button', { name: 'Open navigation' })
  if ((page.viewportSize()?.width ?? 1280) < 768) await menu.click()
  await page.getByRole('navigation', { name: 'Workspace', exact: true }).getByRole('button', { name, exact: true }).click()
}

test('practice uses the LeetCode catalog and manages the public handle in Profile', async ({ page }) => {
  const suffix = randomUUID().replaceAll('-', '').slice(0, 10)
  const email = 'practice-' + suffix + '@example.test'
  const password = 'phase-two-only-password'
  let accepted = false
  await page.route('**/api/v1/me/leetcode-link/preview*', route => route.fulfill({
    json: {
      username: 'waiting-on-verification', realName: null, ranking: null,
      problemsSolved: { all: Number(accepted), easy: Number(accepted), medium: 0, hard: 0 },
      heatmap: { streak: 0, totalActiveDays: 0, activityDays: [] },
      recentSubmissions: accepted ? [{ title: 'Two Sum', titleSlug: 'two-sum', timestamp: Math.floor(Date.now() / 1000) }] : [],
    },
  }))
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  const headers = { Origin: new URL(page.url()).origin, 'X-LeetGrind-Client': 'browser' }
  const registered = await page.request.post('/api/v1/auth/register', {
    headers,
    data: { email, password, displayName: 'Practice Learner', leetcodeHandle: 'waiting-on-verification' },
  })
  expect(registered.status()).toBe(201)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByText('Daily login reward', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Dismiss Daily login reward' }).click()
  await openWorkspace(page, 'Practice')

  await expect(page.getByRole('heading', { name: 'Practice & Mastery' })).toBeVisible()
  const table = page.getByRole('table', { name: 'LeetCode practice problems' })
  await page.getByLabel('Filter problems').fill('number of islands')
  await expect(table.getByRole('row')).toHaveCount(2)
  await expect(table.getByRole('link', { name: 'Solve Number of Islands' })).toHaveAttribute('href', 'https://leetcode.com/problems/number-of-islands/')
  await page.getByRole('button', { name: 'Start timed sprint' }).click()
  const encounter = page.getByRole('region', { name: 'Practice encounter' })
  await expect(encounter.getByRole('timer')).toBeVisible()
  await expect(encounter.getByRole('link', { name: 'Number of Islands' })).toBeVisible()
  await encounter.getByRole('button', { name: 'End sprint' }).click()
  await page.getByRole('button', { name: 'View Trees & graphs problems' }).click()
  await expect(table.getByRole('rowheader', { name: /Invert Binary Tree/ })).toBeVisible()
  await expect(table.getByRole('rowheader', { name: /Two Sum/ })).toHaveCount(0)

  await expect(page.getByRole('region', { name: 'LeetCode profile' })).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'Practice rewards' })).toHaveCount(0)
  await expect(page.getByText('Daily login', { exact: true })).toBeVisible()
  accepted = true
  await page.getByRole('button', { name: 'Refresh public LeetCode activity' }).click()
  await expect(page.getByText('LeetCode accepted submission', { exact: true })).toBeVisible()
  await expect(page.getByRole('region', { name: 'XP history' })).toContainText('5 XP · 0 credited solves')

  await page.getByRole('button', { name: 'Account menu' }).click()
  await page.getByRole('button', { name: 'Profile', exact: true }).click()
  await page.getByLabel('LeetCode handle or profile URL').fill('waiting-on-verification')
  await page.getByRole('button', { name: 'Save LeetCode profile' }).click()
  await expect(page.getByText('Handle saved · public preview available', { exact: false })).toBeVisible()

  await expect(page.getByRole('button', { name: 'Connect demo profile' })).toHaveCount(0)
  await page.getByRole('button', { name: 'Disconnect profile' }).click()
  await expect(page.getByText('Disconnected', { exact: false })).toBeVisible()
  await openWorkspace(page, 'Practice')
  await expect(page.getByLabel('Workspace data')).toHaveCount(0)
  await expect(page.getByRole('region', { name: 'XP history' }).getByText('5 XP · 0 credited solves')).toBeVisible()
})

test('analytics shows history limits, recorded coverage, and selectable UTC periods', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 })
  const suffix = randomUUID().replaceAll('-', '').slice(0, 10)
  const email = 'analytics-' + suffix + '@example.test'
  const password = 'phase-seven-only-password'
  const today = new Date().toISOString().slice(0, 10)
  await page.route('**/api/v1/me/leetcode-link/preview*', route => route.fulfill({
    json: {
      username: 'calendar-example', realName: null, ranking: null,
      problemsSolved: { all: 1, easy: 1, medium: 0, hard: 0 },
      heatmap: { streak: 1, totalActiveDays: 1, activityDays: [{ date: today, submissions: 1 }] }, recentSubmissions: [],
    },
  }))
  await page.goto('/')
  const registered = await page.request.post('/api/v1/auth/register', {
    headers: { Origin: new URL(page.url()).origin, 'X-LeetGrind-Client': 'browser' },
    data: { email, password, displayName: 'Analytics Learner', leetcodeHandle: 'calendar-example' },
  })
  expect(registered.status()).toBe(201)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await openWorkspace(page, 'Analytics')
  await expect(page.getByRole('heading', { name: 'Practice analytics' })).toBeVisible()
  await expect(page.getByText('Live LeetCode sync is awaiting provider access and ownership verification.')).toBeVisible()
  await expect(page.getByText('Historical imports are unavailable.', { exact: false })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Binary Search', exact: true })).toHaveAttribute('href', 'https://leetcode.com/problems/binary-search/')
  await expect(page.getByLabel('Analytics view')).toHaveCount(0)
  const summary = page.getByRole('region', { name: 'Practice summary' })
  await expect(summary.getByText('5', { exact: true })).toBeVisible()
  await expect(summary.getByRole('heading', { name: 'Topic practice coverage' })).toBeVisible()
  const suggestions = page.getByRole('region', { name: 'Recommended problems' })
  await expect(suggestions.getByText('fewer than three mapped problems', { exact: false }).first()).toBeVisible()

  const calendar = page.getByRole('region', { name: 'Coding activity' })
  const days = calendar.getByRole('button', { name: /UTC · \d+ submissions?/ })
  await expect(days).toHaveCount(365)
  await expect(days.last()).toHaveAttribute('data-intensity', '1')
  await days.last().focus()
  await page.keyboard.press('Home')
  await expect(days.first()).toBeFocused()
  await calendar.getByRole('button', { name: 'View week summary' }).click()
  await expect(summary.getByText('All recorded activity', { exact: true })).toHaveCount(0)
  await expect(summary.getByText('No mapped practice in this period.')).toBeVisible()
  await page.getByRole('button', { name: 'Current week', exact: true }).click()
  await expect(summary.getByText('5', { exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(375)

  await page.route(/\/api\/v1\/me\/analytics\?mode=live/, async route => {
    const response = await route.fetch()
    const analytics = await response.json()
    await route.fulfill({ response, json: { ...analytics, coverage: { ...analytics.coverage, state: 'PARTIAL' }, recommendations: [] } })
  }, { times: 1 })
  await page.getByRole('button', { name: 'Refresh analytics' }).click()
  await expect(page.getByText('Coverage is incomplete.', { exact: false })).toBeVisible()
  await expect(suggestions.getByText('No unsolved problems are available in this catalog.')).toBeVisible()
})
