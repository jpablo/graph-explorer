import { instance } from "@viz-js/viz";
const viz = await instance();
const src = `digraph {
    subgraph cluster_0 { a0; a1; a2; }
    subgraph cluster_1 { b0; b1; b2; }
    top -> a0; top -> b0; top -> a1; top -> b1; top -> a2; top -> b2;
    a0->a1->a2; b0->b1->b2;
  }`;
const r = viz.render(src, { format: "dot_json", engine: "dot" });
const d = JSON.parse(r.output);
console.log("keys:", Object.keys(d));
console.log("objects[0..3]:", JSON.stringify((d.objects||[]).slice(0,4), null, 1));
