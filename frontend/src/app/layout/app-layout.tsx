import { useState } from 'react'
import { Outlet } from 'react-router'
import { TabSafetyNotice } from '@/features/auth/components/tab-safety-notice'
import { Dialog } from '@/shared/ui/dialog'
import { SidebarNav } from './sidebar'
import { TopBar } from './top-bar'

/**
 * Signed-in shell (T-67).
 *
 * - Mobile (< 768 px): top bar with a menu button; navigation opens in a full-screen dialog.
 * - Tablet (768–1023 px): fixed sidebar, 12 rem.
 * - Desktop (≥ 1024 px): fixed sidebar, 16 rem.
 * A skip link jumps past the navigation to `#main-content`.
 */
export function AppLayout() {
  const [menuOpen, setMenuOpen] = useState(false)

  return (
    <div className="flex min-h-screen bg-gray-950 text-gray-100">
      <a
        href="#main-content"
        className="sr-only focus:not-sr-only focus:fixed focus:left-2 focus:top-2 focus:z-50 focus:rounded-md focus:bg-sky-700 focus:px-4 focus:py-2"
      >
        Skip to content
      </a>

      <aside className="hidden w-48 shrink-0 flex-col gap-6 border-r border-gray-800 bg-gray-900 p-4 md:flex lg:w-64">
        <p className="px-3 text-lg font-semibold">FinSight AI</p>
        <SidebarNav />
      </aside>

      <Dialog open={menuOpen} title="Menu" onClose={() => setMenuOpen(false)}>
        <SidebarNav onNavigate={() => setMenuOpen(false)} />
      </Dialog>

      <div className="flex min-w-0 flex-1 flex-col">
        <TopBar menuOpen={menuOpen} onOpenMenu={() => setMenuOpen(true)} />
        <main id="main-content" tabIndex={-1} className="flex flex-1 flex-col gap-6 px-4 py-6 focus:outline-none md:px-6 lg:px-8">
          <TabSafetyNotice />
          <Outlet />
        </main>
      </div>
    </div>
  )
}
