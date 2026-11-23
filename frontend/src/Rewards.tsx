import { useEffect, useState } from 'react'
import { ApiError, rewardsApi } from './api'
import type { Group, GroupFeatured, RewardsProgress } from './api'
import { QuestBoard } from './QuestBoard'
import { AchievementBoard } from './AchievementBoard'

export function Rewards({ accountId, section, group, onPractice, onAchievements }: {
  accountId: string
  section: 'quests' | 'achievements'
  group?: Group
  onPractice: () => void
  onAchievements: () => void
}) {
  const [view, setView] = useState<RewardsProgress | null>(null)
  const [groupProgress, setGroupProgress] = useState<GroupFeatured | null>(null)
  const [error, setError] = useState('')
  const groupId = section === 'quests' ? group?.id : undefined

  useEffect(() => {
    let active = true
    let waiting = false
    async function load() {
      if (waiting) return
      waiting = true
      try {
        const [personal, team] = await Promise.allSettled([
          rewardsApi.progress(accountId, 'live'),
          groupId ? rewardsApi.groupFeatured(accountId, groupId, 'live') : Promise.resolve(null),
        ])
        if (!active) return
        setGroupProgress(team.status === 'fulfilled' ? team.value : null)
        if (personal.status === 'rejected') throw personal.reason
        setView(personal.value)
        setError('')
      } catch (failure) {
        if (active) setError(failure instanceof ApiError ? failure.message : 'Rewards could not be loaded.')
      } finally {
        waiting = false
      }
    }

    void load()
    const interval = window.setInterval(() => {
      if (document.visibilityState === 'visible') void load()
    }, 4000)
    return () => {
      active = false
      window.clearInterval(interval)
    }
  }, [accountId, groupId])

  if (section === 'quests') return <QuestBoard rewards={view} groupName={group?.name} group={groupProgress}
    error={error} onPractice={onPractice} onAchievements={onAchievements} />

  return <AchievementBoard rewards={view} error={error} onPractice={onPractice} />
}
