import { instance } from "@viz-js/viz";
const viz = await instance();
const src = `digraph {
  subgraph cluster_0 { a0; a1; a2; }
  subgraph cluster_1 { b0; b1; b2; }
  top -> a0; top -> b0; top -> a1; top -> b1; top -> a2; top -> b2;
  a0->a1->a2; b0->b1->b2;
}`;
const d = JSON.parse(viz.render(src, { format: "json0", engine: "dot" }).output);
console.log("graph bb:", d.bb);
for (const o of d.objects || []) {
  if (o.nodes !== undefined) console.log(`CLUSTER ${o.name}: bb=${o.bb} lp=${o.lp}`);
  else console.log(`  node ${o.name}: pos=${o.pos}`);
}
