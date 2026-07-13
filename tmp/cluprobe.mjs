import { instance } from "@viz-js/viz";
const viz = await instance();
const probes = {
  P1_interleaved: `digraph {
    subgraph cluster_0 { a0; a1; a2; }
    subgraph cluster_1 { b0; b1; b2; }
    top -> a0; top -> b0; top -> a1; top -> b1; top -> a2; top -> b2;
    a0->a1->a2; b0->b1->b2;
  }`,
  P2_03like: `digraph clusters {
    subgraph cluster_0 { label="group A"; a0 -> a1 -> a2; }
    subgraph cluster_1 { label="group B"; b0 -> b1; }
    { rank = same; a0; b0; }
    a2 -> b1; start -> a0; start -> b0;
  }`,
  P3_cross: `digraph {
    subgraph cluster_0 { a0; a1; }
    subgraph cluster_1 { b0; b1; }
    a0 -> b1; b0 -> a1;
    r -> a0; r -> b0;
  }`,
};
for (const [name, src] of Object.entries(probes)) {
  const r = viz.render(src, { format: "json0", engine: "dot" });
  if (r.status !== "success") { console.log(name, "FAIL", r.errors); continue; }
  const d = JSON.parse(r.output);
  const byRank = {};
  for (const o of d.objects || []) {
    if (!o.pos || o.nodes !== undefined) continue; // skip subgraphs
    const [x, y] = o.pos.split(",").map(Number);
    (byRank[y] ||= []).push([x, o.name]);
  }
  console.log(`\n=== ${name} ===`);
  Object.keys(byRank).map(Number).sort((a,b)=>b-a).forEach(y => {
    const row = byRank[y].sort((a,b)=>a[0]-b[0]).map(p=>p[1]).join(", ");
    console.log(`  y=${y}: ${row}`);
  });
}
