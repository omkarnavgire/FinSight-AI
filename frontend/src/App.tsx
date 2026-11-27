import { RouterProvider } from 'react-router'
import { AppProviders } from './app/app-providers'
import { createAppRouter } from './app/router'

const router = createAppRouter()

/** Root component: providers around the router (T-67). */
function App() {
  return (
    <AppProviders>
      <RouterProvider router={router} />
    </AppProviders>
  )
}

export default App
