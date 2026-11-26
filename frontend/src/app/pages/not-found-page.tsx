import { Link } from 'react-router'

/** Shown for any unknown path. */
export function NotFoundPage() {
  return (
    <main className="flex min-h-screen flex-col items-center justify-center gap-4 bg-gray-950 px-4 text-center text-gray-100">
      <h1 className="text-2xl font-semibold">Page not found</h1>
      <p className="text-gray-300">We could not find that page.</p>
      <Link to="/dashboard" className="text-sky-300 underline">
        Go to the dashboard
      </Link>
    </main>
  )
}
