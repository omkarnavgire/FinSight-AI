import type { ReactNode } from 'react'
import { TabSafetyNotice } from './tab-safety-notice'

export interface AuthCardProps {
  title: string
  children: ReactNode
  /** Link row under the form, e.g. "No account? Register". */
  footer: ReactNode
}

/** Centred card shared by the login and register pages. */
export function AuthCard({ title, children, footer }: AuthCardProps) {
  return (
    <main className="flex min-h-screen items-center justify-center bg-gray-950 px-4 py-10 text-gray-100">
      <div className="flex w-full max-w-md flex-col gap-6">
        <p className="text-center text-xl font-semibold">FinSight AI</p>
        <TabSafetyNotice />
        <section aria-labelledby="auth-title" className="rounded-lg border border-gray-800 bg-gray-900 p-6 shadow-lg">
          <h1 id="auth-title" className="mb-6 text-2xl font-semibold">
            {title}
          </h1>
          {children}
        </section>
        <p className="text-center text-sm text-gray-300">{footer}</p>
      </div>
    </main>
  )
}
