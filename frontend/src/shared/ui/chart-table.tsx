export interface ChartRow {
  key: string
  label: string
  /** Pre-formatted exact value, e.g. "1,250.40 USD". */
  valueText: string
}

export interface ChartTableProps {
  caption: string
  labelHeading: string
  valueHeading: string
  rows: readonly ChartRow[]
}

function Rows({ labelHeading, valueHeading, rows }: Omit<ChartTableProps, 'caption'>) {
  return (
    <>
      <thead>
        <tr className="border-b border-gray-700">
          <th scope="col" className="py-2 pr-4 font-medium">{labelHeading}</th>
          <th scope="col" className="py-2 text-right font-medium">{valueHeading}</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((row) => (
          <tr key={row.key} className="border-b border-gray-800">
            <th scope="row" className="py-2 pr-4 font-normal">{row.label}</th>
            <td className="py-2 text-right tabular-nums">{row.valueText}</td>
          </tr>
        ))}
      </tbody>
    </>
  )
}

/**
 * The chart's data as a table, in two copies:
 * - a screen-reader-only table, always in the accessibility tree (closed `<details>` content
 *   is not, in real browsers);
 * - a visible table inside "Show as table" for sighted users, marked `aria-hidden` so screen
 *   readers do not hear the same data twice when it is opened.
 */
export function ChartTable({ caption, labelHeading, valueHeading, rows }: ChartTableProps) {
  return (
    <>
      <table className="sr-only">
        <caption>{caption}</caption>
        <Rows labelHeading={labelHeading} valueHeading={valueHeading} rows={rows} />
      </table>
      <details className="text-sm">
        <summary className="min-h-11 cursor-pointer py-2 text-sky-300 underline">
          Show as table
        </summary>
        <table aria-hidden="true" className="mt-2 w-full border-collapse text-left">
          <Rows labelHeading={labelHeading} valueHeading={valueHeading} rows={rows} />
        </table>
      </details>
    </>
  )
}
