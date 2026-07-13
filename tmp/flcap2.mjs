import { instance } from "@viz-js/viz";
import { readFileSync } from "fs";
const viz = await instance();
for (const f of ["flatl.dot","flatw.dot"]) {
  const j = JSON.parse(viz.renderString(readFileSync(f,"utf8"), { format: "json0" }));
  const b = j.objects.find(o=>o.name==="b");
  const e = j.edges.find(x=>x.lp);
  console.log(f, "b.x=", b.pos.split(",")[0], "lp=", e.lp);
}
