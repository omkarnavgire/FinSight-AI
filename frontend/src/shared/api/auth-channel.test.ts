import { afterEach, describe, expect, it, vi } from 'vitest'
import { broadcastAuthEvent, isAuthChannelSupported, subscribeAuthEvents } from './auth-channel'
import { FakeChannel } from '@/test/fake-broadcast-channel'

afterEach(() => {
  vi.unstubAllGlobals()
  FakeChannel.reset()
})

describe('auth channel', () => {
  it('does nothing without BroadcastChannel', () => {
    vi.stubGlobal('BroadcastChannel', undefined)
    expect(isAuthChannelSupported()).toBe(false)
    expect(() => broadcastAuthEvent('logout')).not.toThrow()
    expect(() => subscribeAuthEvents(() => undefined)()).not.toThrow()
  })

  it('sends only an event type and a tab id, never a token', () => {
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    broadcastAuthEvent('login')
    expect(FakeChannel.sent).toHaveLength(1)
    expect(Object.keys(FakeChannel.sent[0] as object).sort()).toEqual(['source', 'type'])
  })

  it('ignores this tab’s own messages', () => {
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    const listener = vi.fn()
    const stop = subscribeAuthEvents(listener)
    broadcastAuthEvent('logout')
    expect(listener).not.toHaveBeenCalled()
    stop()
  })

  it('delivers events from other tabs and ignores malformed ones', () => {
    vi.stubGlobal('BroadcastChannel', FakeChannel)
    const listener = vi.fn()
    const stop = subscribeAuthEvents(listener)
    const otherTab = new FakeChannel('finsight-auth')
    otherTab.postMessage({ type: 'logout', source: 'other-tab' })
    otherTab.postMessage({ type: 'steal', source: 'other-tab' })
    otherTab.postMessage('logout')
    otherTab.postMessage({ type: 'login' })
    expect(listener).toHaveBeenCalledTimes(1)
    expect(listener).toHaveBeenCalledWith('logout')
    stop()
    otherTab.postMessage({ type: 'login', source: 'other-tab' })
    expect(listener).toHaveBeenCalledTimes(1)
  })
})
