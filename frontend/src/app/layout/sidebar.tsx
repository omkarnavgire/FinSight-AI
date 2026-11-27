import { NavLink } from 'react-router'
import { FraudBadge } from '@/features/fraud'
import { NAV_ITEMS } from './nav-items'

export interface SidebarNavProps {
  /** Called after a link is chosen (closes the mobile menu). */
  onNavigate?: () => void
}

/** Main navigation links. `NavLink` sets `aria-current="page"` on the active one. */
export function SidebarNav({ onNavigate }: SidebarNavProps) {
  return (
    <nav aria-label="Main">
      <ul className="flex flex-col gap-1">
        {NAV_ITEMS.map((item) => (
          <li key={item.path}>
            <NavLink
              to={item.path}
              onClick={onNavigate}
              className={({ isActive }) =>
                `flex min-h-11 items-center rounded-md px-3 text-sm font-medium focus:outline-none focus-visible:ring-2 focus-visible:ring-sky-400 ${
                  isActive ? 'bg-sky-900 text-white' : 'text-gray-300 hover:bg-gray-800 hover:text-white'
                }`
              }
            >
              {item.label}
              {item.path === '/fraud-alerts' ? (
                <span className="ml-auto pl-2">
                  <FraudBadge />
                </span>
              ) : null}
            </NavLink>
          </li>
        ))}
      </ul>
    </nav>
  )
}
