import { CartesianGrid, Legend, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import type { ActivityTrendPointDto } from '../types';

function formatDay(date: string) {
  return new Date(`${date}T00:00:00`).toLocaleDateString(undefined, { month: 'short', day: 'numeric' });
}

/**
 * Platform Activity (dashboard redesign PR4) -- 7 real daily points from
 * GET /admin/dashboard/activity-trend, not a fabricated sparkline. Signups/imports/transactions
 * share one chart rather than three separate ones, since the real question an admin has is
 * whether these moved together -- a signup spike with flat transactions is a different story
 * from both rising at once, and three isolated sparklines wouldn't show that.
 */
export function PlatformActivityChart({ data }: { data: ActivityTrendPointDto[] }) {
  return (
    <ResponsiveContainer width="100%" height={240}>
      <LineChart data={data} margin={{ top: 4, right: 8, left: -16, bottom: 0 }}>
        <CartesianGrid stroke="var(--color-border)" vertical={false} />
        <XAxis
          dataKey="date"
          tickFormatter={formatDay}
          tick={{ fontSize: 11, fill: 'var(--color-muted)' }}
          axisLine={{ stroke: 'var(--color-border)' }}
          tickLine={false}
        />
        <YAxis
          allowDecimals={false}
          tick={{ fontSize: 11, fill: 'var(--color-muted)' }}
          axisLine={false}
          tickLine={false}
          width={32}
        />
        <Tooltip
          labelFormatter={(label) => formatDay(String(label))}
          contentStyle={{
            background: 'var(--color-card)',
            border: '1px solid var(--color-border)',
            borderRadius: 8,
            fontSize: 12,
          }}
        />
        <Legend wrapperStyle={{ fontSize: 12 }} />
        <Line type="monotone" dataKey="transactions" name="Transactions" stroke="rgb(var(--color-primary))" strokeWidth={2} dot={false} />
        <Line type="monotone" dataKey="imports" name="Imports" stroke="var(--color-success)" strokeWidth={2} dot={false} />
        <Line type="monotone" dataKey="signups" name="Signups" stroke="var(--color-muted)" strokeWidth={2} dot={false} />
      </LineChart>
    </ResponsiveContainer>
  );
}
