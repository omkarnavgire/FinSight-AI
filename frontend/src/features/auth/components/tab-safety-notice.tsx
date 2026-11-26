import { Alert } from '@/shared/ui/alert'
import { useAuth } from '../hooks/use-auth'

/**
 * Warns when the browser has no Web Locks API (D-2). Without it, FinSight cannot stop two tabs
 * from refreshing the session at once; the backend then treats the second refresh as token
 * theft and signs the user out on every device. Hidden when tabs can be coordinated.
 */
export function TabSafetyNotice() {
  const { crossTabSafe } = useAuth()
  if (crossTabSafe) return null
  return (
    <Alert tone="warning" title="Use FinSight in one tab" live={false}>
      This browser cannot coordinate sign-in between tabs. If FinSight is open in several tabs, you may be signed
      out everywhere. Keep it in one tab, or use a current version of Chrome, Edge, Firefox or Safari.
    </Alert>
  )
}
