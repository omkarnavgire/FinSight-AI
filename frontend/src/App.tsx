import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { BrowserRouter, Route, Routes } from 'react-router'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: 1,
      staleTime: 30_000,
    },
  },
})

/**
 * Root application component.
 *
 * Provides React Query and React Router contexts. Feature routes will be
 * added in Phase 2 and beyond; only the health placeholder is wired here.
 */
function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <Routes>
          <Route path="/" element={<HealthPlaceholder />} />
        </Routes>
      </BrowserRouter>
    </QueryClientProvider>
  )
}

function HealthPlaceholder() {
  return (
    <main className="flex min-h-screen items-center justify-center bg-gray-950 text-gray-100">
      <div className="text-center">
        <h1 className="text-2xl font-semibold mb-2">FinSight AI</h1>
        <p className="text-gray-400 text-sm">Foundation scaffold — Phase 1</p>
      </div>
    </main>
  )
}

export default App
