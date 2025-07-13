## Change triggered by the Code Mirror

```text
1a. [sourceText -> versionedText]: 1108.529 s,  at: 17:39:30.001Z
Versioned(digraph "G" {
  "a" [id="node:a", label="a1", pos="27,18", height="0.5", width="0.75", fillcolor="#bedbff", style="filled"];
},4,CodeMirror)

2a. [versionedText -> versionedFullGraphV]: 0.021 s,  at: 17:39:30.023Z
Versioned(ViewerGraph(ViewerGraphElements(VectorMap(a -> ViewerNode(a,Attributes(VectorMap(_gvid -> 0, label -> a1, pos -> 27,18, height -> 0.5, width -> 0.75, fillcolor -> #bedbff, invisiblestyle -> false, boldstyle -> false, borderstyle -> solid, cornerstyle -> normal, fillstyle -> true)),None)),Map(),VectorMap(),Map(),Attributes(VectorMap(directed -> true)),Attributes(Map()),Attributes(Map()),Attributes(Map())),G,digraph,0),4,CodeMirror)

3a. [versionedFullGraphV -> fullGraphV]: 0.001 s,  at: 17:39:30.025Z
ViewerGraph(ViewerGraphElements(VectorMap(a -> ViewerNode(a,Attributes(VectorMap(_gvid -> 0, label -> a1, pos -> 27,18, height -> 0.5, width -> 0.75, fillcolor -> #bedbff, invisiblestyle -> false, boldstyle -> false, borderstyle -> solid, cornerstyle -> normal, fillstyle -> true)),None)),Map(),VectorMap(),Map(),Attributes(VectorMap(directed -> true)),Attributes(Map()),Attributes(Map()),Attributes(Map())),G,digraph,0)

4. [fullGraphV -> visibleGraph]: 0.017 s,  at: 17:39:30.042Z
ViewerGraph(ViewerGraphElements(VectorMap(a -> ViewerNode(a,Attributes(VectorMap(_gvid -> 0, label -> a1, pos -> 27,18, height -> 0.5, width -> 0.75, fillcolor -> #bedbff, invisiblestyle -> false, boldstyle -> false, borderstyle -> solid, cornerstyle -> normal, fillstyle -> true)),None)),Map(),VectorMap(),Map(),Attributes(VectorMap(directed -> true)),Attributes(Map(sides -> 5, shape -> box)),Attributes(Map(dir -> both, arrowhead -> vee, arrowtail -> none)),Attributes(Map())),G,digraph,0)

5. [visibleGraph -> visibleDOT]: 0.001 s,  at: 17:39:30.043Z
digraph "G" {
  "a" [id="node:a", label="a1", pos="27,18", height="0.5", width="0.75", fillcolor="#bedbff", style="filled"];
}

6. [visibleDOT -> SVG]: 0.002 s,  at: 17:39:30.045Z
ReactiveSvgElement(<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="-3.6 -40.4 62 44" class="graphviz">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 40)">
<title>G</title>
<polygon fill="white" stroke="none" points="-4,4 -4,-40 58,-40 58,4 -4,4"/>
<!-- a -->
<g id="node:a" class="node">
<title>a</title>
<ellipse fill="#bedbff" stroke="black" cx="27" cy="-18" rx="27" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="27" y="-13.8" font-family="Times,serif" font-size="14.00">a1</text>
</g>
</g>
<rect id="selection-rectangle"/></svg>)
```

