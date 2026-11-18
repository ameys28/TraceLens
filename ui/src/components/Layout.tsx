import { NavLink, Outlet } from 'react-router-dom';
import { api } from '../lib/api';
import { usePoll } from '../lib/usePoll';

const NAV = [
  { to: '/', label: 'Overview', end: true },
  { to: '/traces', label: 'Traces' },
  { to: '/map', label: 'Service map' },
  { to: '/alerts', label: 'Alerts' },
  { to: '/sampling', label: 'Sampling & storage' },
  { to: '/chaos', label: 'Fault injection' },
];

export default function Layout() {
  const { data: open } = usePoll(() => api.alerts('OPEN'), 15000);
  const openCount = open?.length ?? 0;
  return (
    <div className="flex min-h-screen">
      <aside className="sticky top-0 flex h-screen w-56 shrink-0 flex-col border-r border-ink-800 bg-ink-900/60 px-3 py-5">
        <div className="mb-8 flex items-center gap-2 px-2">
          <svg width="22" height="22" viewBox="0 0 32 32" aria-hidden>
            <path d="M6 10h14M10 16h16M8 22h10" stroke="#f5a524" strokeWidth="3" strokeLinecap="round" />
          </svg>
          <span className="text-[15px] font-semibold tracking-tight text-ink-100">TraceLens</span>
        </div>
        <nav className="flex flex-col gap-0.5">
          {NAV.map((n) => (
            <NavLink
              key={n.to}
              to={n.to}
              end={n.end}
              className={({ isActive }) =>
                `flex items-center justify-between rounded-md px-2.5 py-1.5 text-sm ${
                  isActive ? 'bg-ink-800 text-ink-100' : 'text-ink-400 hover:bg-ink-850 hover:text-ink-200'
                }`
              }
            >
              {n.label}
              {n.to === '/alerts' && openCount > 0 && (
                <span className="rounded bg-bad px-1.5 text-[11px] font-semibold text-ink-950">{openCount}</span>
              )}
            </NavLink>
          ))}
        </nav>
        <div className="mt-auto px-2 text-[11px] leading-relaxed text-ink-500">
          Distributed request tracing &amp; failure analysis
        </div>
      </aside>
      <main className="min-w-0 flex-1 px-8 py-6">
        <Outlet />
      </main>
    </div>
  );
}
