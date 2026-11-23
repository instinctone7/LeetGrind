import { randomUUID } from 'node:crypto'
import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

async function openAccountPage(page: Page, name: 'Profile' | 'Settings') {
  if ((page.viewportSize()?.width ?? 1280) < 768) {
    await page.getByRole('button', { name: 'Open navigation' }).click()
  }
  await page.getByRole('button', { name: 'Account menu' }).click()
  await page.getByRole('button', { name, exact: true }).click()
}

test.beforeEach(async ({ page }) => {
  await page.route('**/api/v1/me/leetcode-link/preview*', route => route.fulfill({ json: null }))
})

test('register, sign in, update a profile, refresh, and sign out', async ({ page }) => {
  const suffix = randomUUID().replaceAll('-', '').slice(0, 10)
  const email = 'learner-' + suffix + '@example.test'
  const handle = 'learner_' + suffix
  const password = 'phase-one-only-password'

  await page.goto('/')
  await page.getByRole('button', { name: 'Create an account' }).click()
  await page.getByLabel('Display name').fill('Practice Learner')
  await expect(page.getByLabel('Handle', { exact: true })).toHaveCount(0)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  const leetcode = page.getByLabel('LeetCode username or profile URL')
  await expect(leetcode).toHaveAttribute('required', '')
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(leetcode).toBeFocused()
  await leetcode.fill('https://leetcode.com/u/' + handle + '/')
  await page.getByRole('button', { name: 'Create account', exact: true }).click()
  await expect(page.getByText('Your account is ready.')).toBeVisible()

  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('banner').getByRole('button', { name: 'Profile', exact: true })).toHaveCount(0)
  const accountMenu = page.getByRole('button', { name: 'Account menu' })
  await accountMenu.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('button', { name: 'Profile', exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeVisible()
  await page.keyboard.press('Escape')
  await expect(page.getByRole('button', { name: 'Settings', exact: true })).toBeHidden()
  await expect(accountMenu).toBeFocused()
  await openAccountPage(page, 'Profile')
  await expect(page.getByRole('heading', { name: 'Your profile' })).toBeVisible()
  await expect(page.getByText(/@practice_[a-z]+_[a-f0-9]{16}/)).toBeVisible()
  await expect(page.getByLabel('LeetCode handle or profile URL')).toHaveValue(handle)

  await page.getByLabel('Display name').fill('Group Learner')
  await page.getByRole('button', { name: 'Save profile' }).click()
  await expect(page.getByText('Profile saved.')).toBeVisible()

  const refresh = page.waitForResponse(response => response.url().endsWith('/api/v1/auth/refresh'))
  await page.reload()
  expect((await refresh).status()).toBe(200)
  await openAccountPage(page, 'Profile')
  await expect(page.getByLabel('Display name')).toHaveValue('Group Learner')
  expect(await page.evaluate(() => window.localStorage.length)).toBe(0)
  expect(await page.evaluate(() => document.cookie)).not.toContain('leetgrind_refresh')

  await openAccountPage(page, 'Settings')
  await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
})

test('a stale profile edit cannot change a different browser account', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  const headers = { Origin: new URL(page.url()).origin, 'X-LeetGrind-Client': 'browser' }
  const suffix = randomUUID().replaceAll('-', '').slice(0, 10)
  const password = 'phase-one-only-password'
  const first = { email: 'first-' + suffix + '@example.test', leetcodeHandle: 'first_' + suffix, displayName: 'First Learner' }
  const second = { email: 'second-' + suffix + '@example.test', leetcodeHandle: 'second_' + suffix, displayName: 'Second Learner' }

  for (const profile of [first, second]) {
    const response = await page.request.post('/api/v1/auth/register', { headers, data: { ...profile, password } })
    expect(response.status()).toBe(201)
  }
  await page.getByLabel('Email').fill(first.email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await openAccountPage(page, 'Profile')
  await expect(page.getByLabel('Display name')).toHaveValue(first.displayName)

  // The browser's second tab signs out and switches accounts while this tab remains open.
  expect((await page.request.post('/api/v1/auth/logout', { headers })).status()).toBe(204)
  const switched = await page.request.post('/api/v1/auth/login', { headers, data: { email: second.email, password } })
  expect(switched.status()).toBe(200)
  await page.getByLabel('Display name').fill('Stale edit')
  await page.getByRole('button', { name: 'Save profile' }).click()
  await expect(page.getByRole('alert')).toContainText('The browser account changed.')

  await page.reload()
  await openAccountPage(page, 'Profile')
  await expect(page.getByLabel('Display name')).toHaveValue(second.displayName)
})

test('deleting an account requires the current password and clears the session', async ({ page }) => {
  await page.setViewportSize({ width: 375, height: 812 })
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
  const suffix = randomUUID().replaceAll('-', '').slice(0, 10)
  const email = 'delete-' + suffix + '@example.test'
  const password = 'phase-four-only-password'
  const headers = { Origin: new URL(page.url()).origin, 'X-LeetGrind-Client': 'browser' }
  const registration = await page.request.post('/api/v1/auth/register', {
    headers, data: { email, password, displayName: 'Delete Fixture', leetcodeHandle: 'delete_' + suffix },
  })
  expect(registration.status()).toBe(201)
  await page.getByLabel('Email').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await openAccountPage(page, 'Settings')
  await expect(page.getByRole('heading', { name: 'Settings', exact: true })).toBeVisible()
  await page.getByText('Delete account', { exact: true }).click()
  await page.getByLabel('Current password').fill('wrong-password')
  await page.getByRole('checkbox').check()
  await page.getByRole('button', { name: 'Permanently delete account' }).click()
  await expect(page.getByRole('alert')).toContainText('The current password is incorrect.')
  await page.getByLabel('Current password').fill(password)
  await page.getByRole('button', { name: 'Permanently delete account' }).click()
  await expect(page.getByText('Your account was deleted.')).toBeVisible()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Sign in' })).toBeVisible()
})
