/**
 * Date display for backend `LocalDate` ("2026-03-15") and `LocalDateTime` ("2026-03-15T10:20:30")
 * values. These have no time zone, so they are formatted in UTC to show the same wall-clock
 * values the server sent, whatever the browser's zone.
 */

const LOCAL_DATE = /^(\d{4})-(\d{2})-(\d{2})$/
const LOCAL_DATE_TIME = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?$/

const DATE_FORMAT = new Intl.DateTimeFormat('en-GB', { day: 'numeric', month: 'short', year: 'numeric', timeZone: 'UTC' })
const DATE_TIME_FORMAT = new Intl.DateTimeFormat('en-GB', {
  day: 'numeric',
  month: 'short',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  timeZone: 'UTC',
})
const MONTH_FORMAT = new Intl.DateTimeFormat('en-GB', { month: 'long', year: 'numeric', timeZone: 'UTC' })

/** "15 Mar 2026" for "2026-03-15"; the input itself if it is not a valid date. */
export function formatDate(value: string): string {
  const match = LOCAL_DATE.exec(value)
  if (!match) return value
  return DATE_FORMAT.format(Date.UTC(Number(match[1]), Number(match[2]) - 1, Number(match[3])))
}

/** "15 Mar 2026, 10:20" for "2026-03-15T10:20:30"; the input itself if unreadable. */
export function formatDateTime(value: string): string {
  const match = LOCAL_DATE_TIME.exec(value)
  if (!match) return value
  const [, y, mo, d, h, mi, s] = match
  return DATE_TIME_FORMAT.format(Date.UTC(Number(y), Number(mo) - 1, Number(d), Number(h), Number(mi), Number(s ?? 0)))
}

/** "March 2026" for (2026, 3). */
export function formatMonth(year: number, month: number): string {
  return MONTH_FORMAT.format(Date.UTC(year, month - 1, 1))
}

/** Today's date as "YYYY-MM-DD" in the browser's own zone, for form defaults. */
export function todayIsoDate(now: Date = new Date()): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())}`
}
