import { Navigate, Outlet, useLocation } from 'react-router'
import { PageLoader } from '@/shared/ui/page-loader'
import { redirectTargetFromState } from '@/shared/routing/safe-redirect'
import { useAuth } from '../hooks/use-auth'

/**
 * Wraps `/login` and `/register` (T-66).
 *
 * Signed-in visitors are sent on to the page they asked for (checked by `safeRedirect`),
 * or to `/dashboard`.
 */
export function PublicOnlyRoute() {
  const { status } = useAuth()
  const location = useLocation()

  if (status === 'restoring') {
    return <PageLoader label="Restoring your session" />
  }
  if (status === 'authenticated') {
    return <Navigate to={redirectTargetFromState(location.state)} replace />
  }
  return <Outlet />
}
