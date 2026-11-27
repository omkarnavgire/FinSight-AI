import { ACCOUNT_TYPE_LABELS } from '@/shared/domain/categories'
import { formatDate } from '@/shared/format/format-date'
import { formatMoney } from '@/shared/format/format-money'
import type { AccountDto } from '@/shared/types/api'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'

export interface AccountListProps {
  accounts: readonly AccountDto[]
  onDeactivate: (account: AccountDto) => void
}

function StatusBadge({ active }: { active: boolean }) {
  return active ? <Badge tone="success">Active</Badge> : <Badge tone="neutral">Inactive</Badge>
}

/** Accounts as a table from `md` up and as cards below. Each balance shows its own currency. */
export function AccountList({ accounts, onDeactivate }: AccountListProps) {
  return (
    <>
      <table className="hidden w-full border-collapse text-left text-sm md:table">
        <caption className="sr-only">Your accounts</caption>
        <thead>
          <tr className="border-b border-gray-700 text-gray-300">
            <th scope="col" className="py-2 pr-4 font-medium">Name</th>
            <th scope="col" className="py-2 pr-4 font-medium">Type</th>
            <th scope="col" className="py-2 pr-4 text-right font-medium">Balance</th>
            <th scope="col" className="py-2 pr-4 font-medium">Status</th>
            <th scope="col" className="py-2 font-medium"><span className="sr-only">Actions</span></th>
          </tr>
        </thead>
        <tbody>
          {accounts.map((account) => (
            <tr key={account.id} className={`border-b border-gray-800 ${account.active ? '' : 'text-gray-500'}`}>
              <th scope="row" className="py-3 pr-4 font-medium">{account.name}</th>
              <td className="py-3 pr-4">{ACCOUNT_TYPE_LABELS[account.type]}</td>
              <td className="py-3 pr-4 text-right tabular-nums">{formatMoney(account.balance, account.currency)}</td>
              <td className="py-3 pr-4"><StatusBadge active={account.active} /></td>
              <td className="py-3 text-right">
                {account.active ? (
                  <Button variant="ghost" onClick={() => onDeactivate(account)} ariaLabel={`Deactivate ${account.name}`}>
                    Deactivate
                  </Button>
                ) : null}
              </td>
            </tr>
          ))}
        </tbody>
      </table>

      <ul className="flex flex-col gap-3 md:hidden" aria-label="Your accounts">
        {accounts.map((account) => (
          <li key={account.id} className={`rounded-lg border border-gray-800 bg-gray-900 p-4 ${account.active ? '' : 'opacity-70'}`}>
            <div className="flex items-start justify-between gap-3">
              <div>
                <p className="font-semibold">{account.name}</p>
                <p className="text-sm text-gray-400">
                  {ACCOUNT_TYPE_LABELS[account.type]} · opened {formatDate(account.createdAt.slice(0, 10))}
                </p>
              </div>
              <StatusBadge active={account.active} />
            </div>
            <p className="mt-2 text-lg tabular-nums">{formatMoney(account.balance, account.currency)}</p>
            {account.active ? (
              <div className="mt-3">
                <Button variant="secondary" onClick={() => onDeactivate(account)} ariaLabel={`Deactivate ${account.name}`}>
                  Deactivate
                </Button>
              </div>
            ) : null}
          </li>
        ))}
      </ul>
    </>
  )
}
