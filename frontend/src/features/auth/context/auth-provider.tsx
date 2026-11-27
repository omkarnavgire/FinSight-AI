/**
 * Owns the signed-in state (T-66).
 *
 * - Boot: restores the session once from the HttpOnly refresh cookie (`restoreSession`, memoised,
 *   so React StrictMode's double effect sends one `/auth/refresh`).
 * - Expiry: registers the session-expired handler used by the Axios interceptor.
 * - Tabs: reacts to login/logout events from other tabs (`auth-channel`), never sharing a token.
 * - Sign-out of any kind clears the in-memory token, the restore memo and the query cache, so no
 *   user data stays on screen. `ProtectedRoute` then sends the visitor to `/login`.
 */
import { type ReactNode, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { clearAccessToken, setAccessToken } from '@/shared/api/auth-token'
import { setSessionExpiredHandler } from '@/shared/api/auth-events'
import { broadcastAuthEvent, subscribeAuthEvents } from '@/shared/api/auth-channel'
import { isCrossTabLockSupported } from '@/shared/api/refresh-lock'
import { AuthContext, type AuthContextValue, type AuthStatus, type SessionEndReason } from './auth-context'
import { type AuthUser, readUserFromToken } from '../lib/jwt-claims'
import { resetSessionRestore, restoreSession } from '../lib/restore-session'

interface AuthState {
  status: AuthStatus
  user: AuthUser | null
  endReason: SessionEndReason | null
}

const RESTORING: AuthState = { status: 'restoring', user: null, endReason: null }

function signedIn(token: string): AuthState {
  return { status: 'authenticated', user: readUserFromToken(token), endReason: null }
}

function signedOut(endReason: SessionEndReason | null): AuthState {
  return { status: 'anonymous', user: null, endReason }
}

export interface AuthProviderProps {
  children: ReactNode
}

/** Provides {@link AuthContext}. Must sit inside `QueryClientProvider`. */
export function AuthProvider({ children }: AuthProviderProps) {
  const queryClient = useQueryClient()
  const [state, setState] = useState<AuthState>(RESTORING)
  const statusRef = useRef<AuthStatus>(state.status)

  useEffect(() => {
    statusRef.current = state.status
  }, [state.status])

  const dropSession = useCallback(
    (reason: SessionEndReason) => {
      clearAccessToken()
      resetSessionRestore()
      queryClient.clear()
      setState(signedOut(reason))
    },
    [queryClient],
  )

  useEffect(() => {
    let active = true
    void restoreSession().then((token) => {
      if (!active) return
      setState((current) => {
        if (current.status !== 'restoring') return current
        return token === null ? signedOut(null) : signedIn(token)
      })
    })
    return () => {
      active = false
    }
  }, [])

  useEffect(() => {
    setSessionExpiredHandler(() => dropSession('expired'))
    return () => setSessionExpiredHandler(null)
  }, [dropSession])

  useEffect(
    () =>
      subscribeAuthEvents((type) => {
        if (type === 'logout') {
          if (statusRef.current === 'authenticated') dropSession('remote-logout')
          return
        }
        if (statusRef.current !== 'anonymous') return
        resetSessionRestore()
        void restoreSession().then((token) => {
          if (token !== null && statusRef.current === 'anonymous') setState(signedIn(token))
        })
      }),
    [dropSession],
  )

  const establishSession = useCallback((accessToken: string) => {
    setAccessToken(accessToken)
    resetSessionRestore()
    setState(signedIn(accessToken))
    broadcastAuthEvent('login')
  }, [])

  const endSession = useCallback(() => {
    dropSession('logout')
    broadcastAuthEvent('logout')
  }, [dropSession])

  const value = useMemo<AuthContextValue>(
    () => ({ ...state, crossTabSafe: isCrossTabLockSupported(), establishSession, endSession }),
    [state, establishSession, endSession],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
