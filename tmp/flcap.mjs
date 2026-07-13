import { instance } from "@viz-js/viz";
import { readFileSync } from "fs";
const viz = await instance();
const src = readFileSync("flatl.dot", "utf8");
const j = JSON.parse(viz.renderString(src, { format: "json0" }));
console.log("bb", j.bb);
for (const o of j.objects) console.log("node", o.name, o.pos);
for (const e of j.edges) console.log("edge", e.tail, "->", e.head, "lp=", e.lp, "pos=", e.pos);
