/**
 * In-memory BroadcastChannel for tests. Delivers each message to every other open instance
 * with the same name, like separate tabs. Install with `vi.stubGlobal('BroadcastChannel', FakeChannel)`.
 */
export class FakeChannel {
  static open: FakeChannel[] = []
  static sent: unknown[] = []
  onmessage: ((event: MessageEvent<unknown>) => void) | null = null

  constructor(readonly name: string) {
    FakeChannel.open.push(this)
  }

  postMessage(data: unknown): void {
    FakeChannel.sent.push(data)
    for (const other of FakeChannel.open) {
      if (other !== this && other.name === this.name) other.onmessage?.({ data } as MessageEvent<unknown>)
    }
  }

  close(): void {
    FakeChannel.open = FakeChannel.open.filter((channel) => channel !== this)
  }

  /** Clears all channels and sent messages between tests. */
  static reset(): void {
    FakeChannel.open = []
    FakeChannel.sent = []
  }
}

/** Posts a message as if another tab sent it on `finsight-auth`. */
export function sendFromOtherTab(type: string): void {
  const channel = new FakeChannel('finsight-auth')
  channel.postMessage({ type, source: 'other-tab' })
  channel.close()
}
