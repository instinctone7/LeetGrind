import { useCallback, useEffect, useState } from 'react'
import { practiceApi } from './api'
import type { RewardEvent } from './api'
import { Icon } from './Icons'
import { usePublicLeetCode } from './PublicLeetCode'

const badges: Record<string, string> = { FIRST_SOLVE: 'First solve', THREE_DAY_STREAK: 'Three-day streak' }

function seenEvents(key: string): Set<string> {
  try {
    return new Set<string>((sessionStorage.getItem(key) ?? '').split(',').filter(Boolean))
  } catch {
    return new Set()
  }
}

type ToastEvent = Omit<RewardEvent, 'kind'> & { kind: RewardEvent['kind'] | 'PUBLIC_ACCEPTED' }
const presentation = {
  DAILY_LOGIN: { title: 'Daily login reward', icon: 'local_fire_department' },
  BASE_SOLVE: { title: 'Problem solved', icon: 'code' },
  ACHIEVEMENT: { title: 'Achievement unlocked', icon: 'military_tech' },
  PUBLIC_ACCEPTED: { title: 'LeetCode accepted submission', icon: 'code' },
}

function Toast({ event, dismiss }: { event: ToastEvent; dismiss: (id: string) => void }) {
  useEffect(() => {
    const timer = window.setTimeout(() => dismiss(event.id), 7500)
    return () => window.clearTimeout(timer)
  }, [event.id, dismiss])
  const { title, icon } = presentation[event.kind]
  const label = event.kind === 'ACHIEVEMENT' ? badges[event.label ?? ''] ?? event.label : event.label
  return <div className="reward-toast" data-kind={event.kind}>
    <Icon name={icon} />
    <div><strong>{title}</strong>{label && <span>{label}</span>}</div>
    {event.amount !== null && <span className="reward-toast-xp">+{event.amount} XP</span>}
    <button type="button" className="icon-button" aria-label={'Dismiss ' + title}
      onClick={() => dismiss(event.id)}><Icon name="close" /></button>
  </div>
}

export function RewardToasts({ accountId }: { accountId: string }) {
  const { newAcceptances } = usePublicLeetCode()
  const [events, setEvents] = useState<ToastEvent[]>([])
  const dismiss = useCallback((id: string) => setEvents(previous => previous.filter(event => event.id !== id)), [])

  useEffect(() => {
    if (newAcceptances.length === 0) return
    const previews: ToastEvent[] = newAcceptances.map(item => ({
      id: `preview:${item.titleSlug}:${item.timestamp}`, kind: 'PUBLIC_ACCEPTED', label: item.title, amount: null,
      occurredAt: new Date(item.timestamp * 1000).toISOString(),
    }))
    setEvents(previous => [...previous, ...previews.filter(event => !previous.some(item => item.id === event.id))])
  }, [newAcceptances])

  useEffect(() => {
    const key = 'leetgrind-reward-events:' + accountId
    const seen = seenEvents(key)
    let active = true
    let waiting = false
    let nextCheckIn = 0

    async function load() {
      if (waiting || document.visibilityState !== 'visible') return
      waiting = true
      try {
        if (Date.now() >= nextCheckIn) {
          const result = await practiceApi.checkIn(accountId)
          nextCheckIn = Date.parse(result.nextCheckInAt)
        }
        if (!active) return
        const recent = await practiceApi.rewardEvents(accountId)
        if (!active) return
        const unseen = recent.reverse().filter(event => !seen.has(event.id))
        if (unseen.length === 0) return
        unseen.forEach(event => seen.add(event.id))
        try {
          sessionStorage.setItem(key, [...seen].slice(-100).join(','))
        } catch {
          // In-memory deduplication still works when browser storage is unavailable.
        }
        setEvents(previous => [...previous, ...unseen])
      } catch {
        // The next visible poll retries requests interrupted by a network or session change.
      } finally {
        waiting = false
      }
    }

    void load()
    const timer = window.setInterval(load, 8000)
    document.addEventListener('visibilitychange', load)
    return () => {
      active = false
      window.clearInterval(timer)
      document.removeEventListener('visibilitychange', load)
    }
  }, [accountId])

  return <div className="reward-toasts" role="status" aria-live="polite">
    {events.slice(0, 3).map(event => <Toast key={event.id} event={event} dismiss={dismiss} />)}
  </div>
}
