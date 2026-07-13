import { instance } from "@viz-js/viz";
const viz = await instance();
// N1: cluster_1 nested inside cluster_0, plus a sibling node; a chain crossing levels.
const src = `digraph {
  subgraph cluster_0 {
    label="outer";
    subgraph cluster_1 { label="inner"; a; b; }
    c;
  }
  d;
  a -> b; b -> c; c -> d; d -> a;
}
`;
const d = JSON.parse(viz.render(src, { format: "json0", engine: "dot" }).output);
console.log("graph bb:", d.bb);
for (const o of d.objects || []) {
  if (o.nodes !== undefined) console.log(`CLUSTER ${o.name}: bb=${o.bb} lp=${o.lp} nodes=${o.nodes} edges=${o.edges}`);
  else console.log(`  node ${o.name}: pos=${o.pos}`);
}
