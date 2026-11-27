import { useAuth } from '@/features/auth/hooks/use-auth'
import { useLogout } from '@/features/auth/hooks/use-logout'
import { Button } from '@/shared/ui/button'

export interface TopBarProps {
  menuOpen: boolean
  onOpenMenu: () => void
}

/** Top bar: menu button below `md`, the signed-in email, and logout. */
export function TopBar({ menuOpen, onOpenMenu }: TopBarProps) {
  const { user } = useAuth()
  const logout = useLogout()

  return (
    <header className="flex min-h-14 items-center justify-between gap-3 border-b border-gray-800 bg-gray-900 px-4">
      <div className="flex items-center gap-2">
        <Button variant="ghost" className="md:hidden" onClick={onOpenMenu} ariaLabel="Open menu" ariaExpanded={menuOpen}>
          <span aria-hidden="true">☰</span>
        </Button>
        <span className="text-base font-semibold md:hidden">FinSight AI</span>
      </div>
      <div className="flex min-w-0 items-center gap-3">
        {user === null ? null : (
          <span className="hidden truncate text-sm text-gray-300 sm:inline" title={user.email}>
            {user.email}
          </span>
        )}
        <Button variant="secondary" onClick={() => logout.mutate()} busy={logout.isPending}>
          Log out
        </Button>
      </div>
    </header>
  )
}
