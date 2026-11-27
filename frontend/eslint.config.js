import js from '@eslint/js'
import globals from 'globals'
import reactHooks from 'eslint-plugin-react-hooks'
import reactRefresh from 'eslint-plugin-react-refresh'
import tseslint from 'typescript-eslint'

export default tseslint.config(
  { ignores: ['dist'] },
  {
    extends: [js.configs.recommended, ...tseslint.configs.strict],
    files: ['**/*.{ts,tsx}'],
    languageOptions: {
      ecmaVersion: 2022,
      globals: globals.browser,
    },
    plugins: {
      'react-hooks': reactHooks,
      'react-refresh': reactRefresh,
    },
    rules: {
      ...reactHooks.configs.recommended.rules,
      'react-refresh/only-export-components': [
        'warn',
        { allowConstantExport: true },
      ],
      // Enforce coding standards: no any
      '@typescript-eslint/no-explicit-any': 'error',
      // D-7: auth tokens live in memory only; no Web Storage anywhere in the app.
      'no-restricted-globals': [
        'error',
        { name: 'localStorage', message: 'Web Storage is not allowed (D-7). Keep tokens in memory.' },
        { name: 'sessionStorage', message: 'Web Storage is not allowed (D-7). Keep tokens in memory.' },
      ],
      'no-restricted-properties': [
        'error',
        ...['window', 'globalThis', 'self'].flatMap((object) => [
          { object, property: 'localStorage', message: 'Web Storage is not allowed (D-7). Keep tokens in memory.' },
          { object, property: 'sessionStorage', message: 'Web Storage is not allowed (D-7). Keep tokens in memory.' },
        ]),
      ],
    },
  },
  {
    // Test files and helpers never run under the dev server's fast refresh.
    files: ['src/test/**', '**/*.test.tsx'],
    rules: {
      'react-refresh/only-export-components': 'off',
    },
  },
)
