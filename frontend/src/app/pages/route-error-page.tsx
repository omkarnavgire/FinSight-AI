import { Link } from 'react-router'

/**
 * Router `errorElement`: shown when a page throws while rendering. The error itself is never
 * displayed (it may hold internal details); the user gets a way back.
 */
export function RouteErrorPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-gray-950 px-4 text-center text-gray-100">
      <h1 className="text-2xl font-semibold">Something went wrong</h1>
      <p className="text-gray-300">This page hit a problem. Please try again.</p>
      <Link to="/dashboard" reloadDocument className="text-sky-300 underline">
        Reload the dashboard
      </Link>
    </main>
  )
}
