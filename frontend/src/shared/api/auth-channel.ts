/**
 * Login and logout events between tabs (D-3).
 *
 * Uses BroadcastChannel to tell other tabs of this origin that the user signed in or out.
 * Messages carry only an event type and a tab id: never a token or any user data.
 * Where BroadcastChannel is missing, nothing is sent and other tabs notice on their next refresh.
 */

const CHANNEL_NAME = 'finsight-auth'

export type AuthEventType = 'login' | 'logout'

interface AuthChannelMessage {
  type: AuthEventType
  source: string
}

/** Identifies this tab so it ignores its own messages. Not a secret. */
const TAB_ID =
  typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function'
    ? crypto.randomUUID()
    : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`

/** True when this browser can send events between tabs. */
export function isAuthChannelSupported(): boolean {
  return typeof BroadcastChannel !== 'undefined'
}

/** Tells other tabs that this tab signed in or out. */
export function broadcastAuthEvent(type: AuthEventType): void {
  if (!isAuthChannelSupported()) return
  const channel = new BroadcastChannel(CHANNEL_NAME)
  const message: AuthChannelMessage = { type, source: TAB_ID }
  channel.postMessage(message)
  channel.close()
}

/**
 * Listens for events from other tabs.
 *
 * @param listener called with the event type; malformed messages are ignored
 * @returns a function that stops listening
 */
export function subscribeAuthEvents(listener: (type: AuthEventType) => void): () => void {
  if (!isAuthChannelSupported()) return () => undefined
  const channel = new BroadcastChannel(CHANNEL_NAME)
  channel.onmessage = (event: MessageEvent<unknown>) => {
    const type = readEventType(event.data)
    if (type !== null) listener(type)
  }
  return () => channel.close()
}

function readEventType(data: unknown): AuthEventType | null {
  if (typeof data !== 'object' || data === null) return null
  const { type, source } = data as Partial<Record<keyof AuthChannelMessage, unknown>>
  if (source === TAB_ID || typeof source !== 'string') return null
  return type === 'login' || type === 'logout' ? type : null
}
