import { Navigate, Outlet, useLocation } from 'react-router'
import { PageLoader } from '@/shared/ui/page-loader'
import { loginRedirectState } from '@/shared/routing/safe-redirect'
import { useAuth } from '../hooks/use-auth'

/**
 * Guards signed-in pages (T-66).
 *
 * Shows a loader while the session is being restored, sends signed-out visitors to `/login`
 * (remembering where they were going), and renders the child routes otherwise.
 * This is a UI guard only; the backend checks every request.
 */
export function ProtectedRoute() {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'restoring') {
    return <PageLoader label="Restoring your session" />
  }
  if (status === 'anonymous') {
    return <Navigate to="/login" replace state={loginRedirectState(location)} />
  }
  return <Outlet />
}
