import { instance } from "@viz-js/viz";
const viz = await instance();
const shapes = ["cylinder","doubleoctagon","tripleoctagon","Mdiamond","Msquare","Mcircle","star","egg","note","tab","folder","box3d","component","underline","cds","promoter"];
for (const s of shapes) {
  const src = `digraph { a [shape=${s}, label="X"]; }`;
  const d = JSON.parse(viz.render(src, { format: "json0", engine: "dot" }).output);
  const n = d.objects.find(o => o.name === "a");
  console.log(`${s}: w=${n.width} h=${n.height}`);
}
