import { type ReactNode, useState } from 'react'
import { QueryClientProvider } from '@tanstack/react-query'
import { AuthProvider } from '@/features/auth/context/auth-provider'
import { createQueryClient } from './query-client'

export interface AppProvidersProps {
  children: ReactNode
}

/** App-wide providers: React Query, then auth (auth clears the query cache on sign-out). */
export function AppProviders({ children }: AppProvidersProps) {
  const [queryClient] = useState(createQueryClient)
  return (
    <QueryClientProvider client={queryClient}>
      <AuthProvider>{children}</AuthProvider>
    </QueryClientProvider>
  )
}
