import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'

const DAY = 86_400_000
const fullDate = new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeZone: 'UTC' })
const monthLabel = new Intl.DateTimeFormat(undefined, { month: 'short', timeZone: 'UTC' })
const number = new Intl.NumberFormat()
const moves: Record<string, number> = { ArrowLeft: -7, ArrowRight: 7, ArrowUp: -1, ArrowDown: 1 }

export function ActivityGrid({ today, activity, publicProfile, onWeek }: {
  today: string
  activity: { date: string; count: number }[]
  publicProfile: boolean
  onWeek: (monday: string) => void
}) {
  const [selected, setSelected] = useState(364)
  const scroll = useRef<HTMLDivElement>(null)
  const buttons = useRef<Array<HTMLButtonElement | null>>([])
  const latest = Date.parse(today + 'T00:00:00Z')
  const first = latest - 364 * DAY
  const leading = (new Date(first).getUTCDay() + 6) % 7
  const counts = new Map(activity.map(item => [item.date, item.count]))
  const days = Array.from({ length: 365 }, (_, index) => {
    const date = new Date(first + index * DAY)
    const key = date.toISOString().slice(0, 10)
    return { date, key, count: counts.get(key) ?? 0, index }
  })
  const cells = [...Array.from({ length: leading }, () => null), ...days]
  const weeks = Array.from({ length: Math.ceil(cells.length / 7) }, (_, index) => cells.slice(index * 7, index * 7 + 7))
  const activeDays = days.filter(day => day.count > 0).length
  const total = days.reduce((sum, day) => sum + day.count, 0)
  const unit = publicProfile ? 'submission' : 'recorded solve'
  const selectedDay = days[selected]

  useEffect(() => {
    if (scroll.current) scroll.current.scrollLeft = scroll.current.scrollWidth
  }, [today])

  function describe(day: (typeof days)[number]) {
    return `${fullDate.format(day.date)} UTC · ${number.format(day.count)} ${unit}${day.count === 1 ? '' : 's'}`
  }

  function move(event: KeyboardEvent<HTMLButtonElement>, index: number) {
    const offset = moves[event.key]
    if (offset === undefined && event.key !== 'Home' && event.key !== 'End') return
    event.preventDefault()
    const next = event.key === 'Home' ? 0 : event.key === 'End' ? days.length - 1
      : Math.max(0, Math.min(days.length - 1, index + offset))
    setSelected(next)
    buttons.current[next]?.focus()
  }

  function chooseWeek() {
    const weekday = (selectedDay.date.getUTCDay() + 6) % 7
    onWeek(new Date(selectedDay.date.getTime() - weekday * DAY).toISOString().slice(0, 10))
  }

  return <section className="analytics-activity" aria-labelledby="progression-heading">
    <div className="analytics-heading">
      <div>
        <h2 id="progression-heading">Coding activity</h2>
        <p className="muted">{number.format(total)} {unit}{total === 1 ? '' : 's'} in the last 365 days</p>
      </div>
      <span className="muted">{number.format(activeDays)} active {activeDays === 1 ? 'day' : 'days'} · {publicProfile ? 'Public LeetCode' : 'LeetGrind'} · UTC</span>
    </div>
    <div className="activity-scroll" ref={scroll}>
      <div className="activity-heatmap" aria-label="Daily activity calendar">
        {weeks.map((week, weekIndex) => {
          const month = week.find(day => day?.date.getUTCDate() === 1)
          return <div className="activity-week" key={weekIndex}>
            <span className="activity-month" aria-hidden="true">{month ? monthLabel.format(month.date) : ''}</span>
            {week.map((day, weekday) => day ? <button type="button" key={day.key}
              className="activity-cell" data-intensity={Math.min(day.count, 4)} tabIndex={day.index === selected ? 0 : -1}
              aria-label={describe(day)} aria-pressed={day.index === selected} title={describe(day)}
              ref={button => { buttons.current[day.index] = button }}
              onFocus={() => setSelected(day.index)} onClick={() => setSelected(day.index)}
              onKeyDown={event => move(event, day.index)} /> :
              <span className="activity-spacer" key={weekday} aria-hidden="true" />)}
          </div>
        })}
      </div>
    </div>
    <div className="activity-footer">
      <div className="activity-selection">
        <span role="status">{describe(selectedDay)}</span>
        <button type="button" className="text-button" onClick={chooseWeek}>View week summary</button>
      </div>
      <div className="activity-legend" aria-label="Activity intensity, less to more">
        <span>Less</span>{[0, 1, 2, 3, 4].map(level => <span key={level} className="activity-cell" data-intensity={level} aria-hidden="true" />)}<span>More</span>
      </div>
    </div>
  </section>
}
