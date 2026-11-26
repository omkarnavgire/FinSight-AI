import { createContext } from 'react'
import type { AuthUser } from '../lib/jwt-claims'

/** `restoring` until the boot refresh settles, then `authenticated` or `anonymous`. */
export type AuthStatus = 'restoring' | 'authenticated' | 'anonymous'

/** Why the last session ended, for the login page message. */
export type SessionEndReason = 'logout' | 'expired' | 'remote-logout'

export interface AuthContextValue {
  status: AuthStatus
  /** Display data from the token; `null` when signed out or unreadable. */
  user: AuthUser | null
  endReason: SessionEndReason | null
  /** False when Web Locks is missing: refresh cannot be coordinated across tabs (D-2). */
  crossTabSafe: boolean
  /** Starts a session after login or register, and tells other tabs. */
  establishSession: (accessToken: string) => void
  /** Ends the session locally after logout, and tells other tabs. */
  endSession: () => void
}

export const AuthContext = createContext<AuthContextValue | null>(null)
