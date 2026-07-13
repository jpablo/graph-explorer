import { instance } from "@viz-js/viz";
const viz = await instance();
const probes = {
  // C1: two single-rank clusters, shared parent, interleaved edges, NO long edges
  C1_singlerank: `digraph {
    r -> a; r -> b; r -> c; r -> d;
    subgraph cluster_0 { a; c; }
    subgraph cluster_1 { b; d; }
  }`,
  // C2: two clusters each a vertical chain (multi-rank, 1 node/rank), interleaved parent
  C2_chains: `digraph {
    subgraph cluster_0 { a0; a1; a2; }
    subgraph cluster_1 { b0; b1; b2; }
    top -> a0; top -> b0; top -> a1; top -> b1; top -> a2; top -> b2;
    a0->a1->a2; b0->b1->b2;
  }`,
};
for (const [name, src] of Object.entries(probes)) {
  const d = JSON.parse(viz.render(src, { format: "json0", engine: "dot" }).output);
  console.log(`\n=== ${name} ===  bb=${d.bb}`);
  for (const o of d.objects || []) {
    if (o.nodes !== undefined) console.log(`  CLUSTER ${o.name}: bb=${o.bb}`);
  }
  const byRank = {};
  for (const o of d.objects || []) {
    if (o.nodes !== undefined || !o.pos) continue;
    const [x,y]=o.pos.split(",").map(Number);
    (byRank[y]??=[]).push([x,o.name]);
  }
  Object.keys(byRank).map(Number).sort((a,b)=>b-a).forEach(y=>{
    console.log(`  y=${y}: ${byRank[y].sort((a,b)=>a[0]-b[0]).map(p=>`${p[1]}@${p[0]}`).join("  ")}`);
  });
}
