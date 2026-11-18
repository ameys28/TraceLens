import { useMemo, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { Background, MarkerType, Position, ReactFlow, type Edge as FlowEdge, type Node } from '@xyflow/react';
import '@xyflow/react/dist/style.css';
import { api, type DependencyGraph } from '../lib/api';
import { errorColor, fmtDuration, fmtPct, serviceColor } from '../lib/format';
import { usePoll } from '../lib/usePoll';
import { Empty, ErrorBox, PageHeader, WindowSelect } from '../components/ui';

/** Layered layout: each service sits one column right of its deepest caller. */
function layout(graph: DependencyGraph): Record<string, { x: number; y: number }> {
  const callers = new Map<string, string[]>();
  graph.nodes.forEach((n) => callers.set(n.id, []));
  graph.edges.forEach((e) => { if (e.caller !== e.callee) callers.get(e.callee)?.push(e.caller); });
  const level = new Map<string, number>();
  const depth = (id: string, seen: Set<string>): number => {
    if (level.has(id)) return level.get(id)!;
    if (seen.has(id)) return 0; // cycle
    seen.add(id);
    const parents = callers.get(id) ?? [];
    const d = parents.length === 0 ? 0 : Math.max(...parents.map((p) => depth(p, seen) + 1));
    level.set(id, d);
    return d;
  };
  graph.nodes.forEach((n) => depth(n.id, new Set()));
  const columns = new Map<number, string[]>();
  [...level.entries()].sort(([a], [b]) => a.localeCompare(b)).forEach(([id, l]) => columns.set(l, [...(columns.get(l) ?? []), id]));
  const pos: Record<string, { x: number; y: number }> = {};
  columns.forEach((ids, l) => ids.forEach((id, i) => { pos[id] = { x: l * 300, y: i * 150 - ((ids.length - 1) * 150) / 2 }; }));
  return pos;
}

export default function ServiceMap() {
  const [win, setWin] = useState(15);
  const navigate = useNavigate();
  const { data, error } = usePoll(() => api.dependencies(win), 10000, [win]);

  const { nodes, edges } = useMemo(() => {
    if (!data) return { nodes: [] as Node[], edges: [] as FlowEdge[] };
    const pos = layout(data);
    const maxCalls = Math.max(1, ...data.edges.map((e) => e.calls));
    const nodes: Node[] = data.nodes.map((n) => {
      const rate = n.stats?.errorRate ?? 0;
      return {
        id: n.id,
        position: pos[n.id] ?? { x: 0, y: 0 },
        sourcePosition: Position.Right,
        targetPosition: Position.Left,
        data: {
          label: (
            <div className="text-left">
              <div className="flex items-center gap-1.5 text-[13px] font-medium text-ink-100">
                <span className="h-2 w-2 rounded-full" style={{ background: serviceColor(n.id) }} />{n.id}
              </div>
              {n.stats ? (
                <div className="mono mt-1.5 grid grid-cols-3 gap-2 text-[10px] text-ink-400">
                  <span><span className="block text-ink-500">req/s</span>{n.stats.rps.toFixed(1)}</span>
                  <span><span className="block text-ink-500">err</span><span style={{ color: errorColor(rate) }}>{fmtPct(rate)}</span></span>
                  <span><span className="block text-ink-500">p95</span>{fmtDuration(n.stats.p95Us)}</span>
                </div>
              ) : <div className="mt-1 text-[10px] text-ink-500">external / no entry spans</div>}
            </div>
          ),
        },
        style: {
          background: '#111418', border: `1.5px solid ${rate >= 0.02 ? errorColor(rate) : '#2a3038'}`,
          borderRadius: 8, padding: 10, width: 190, color: '#d6dbe2',
        },
      };
    });
    const edges: FlowEdge[] = data.edges.map((e) => ({
      id: `${e.caller}->${e.callee}`,
      source: e.caller,
      target: e.callee,
      label: `${e.calls.toLocaleString()} calls · ${fmtPct(e.errorRate)} err · ${fmtDuration(e.avgUs)}`,
      labelStyle: { fill: '#b4bcc8', fontSize: 10, fontFamily: 'IBM Plex Mono' },
      labelBgStyle: { fill: '#0b0d10' },
      style: { stroke: e.errorRate >= 0.02 ? errorColor(e.errorRate) : '#5b6573', strokeWidth: 1 + (e.calls / maxCalls) * 3 },
      markerEnd: { type: MarkerType.ArrowClosed, color: e.errorRate >= 0.02 ? errorColor(e.errorRate) : '#5b6573' },
      animated: e.errorRate >= 0.05,
    }));
    return { nodes, edges };
  }, [data]);

  return (
    <>
      <PageHeader
        title="Service map"
        subtitle="Built from parent/child spans that cross service boundaries. Edge width = call volume, red = errors."
        right={<WindowSelect value={win} onChange={setWin} />}
      />
      <ErrorBox error={error} />
      <div className="panel h-[640px] overflow-hidden">
        {nodes.length === 0 ? <Empty>No dependencies observed yet.</Empty> : (
          <ReactFlow
            nodes={nodes}
            edges={edges}
            fitView
            fitViewOptions={{ padding: 0.25 }}
            nodesConnectable={false}
            onNodeClick={(_, n) => navigate(`/services/${encodeURIComponent(n.id)}`)}
            colorMode="dark"
            proOptions={{ hideAttribution: true }}
          >
            <Background color="#1c2127" gap={24} />
          </ReactFlow>
        )}
      </div>
    </>
  );
}
