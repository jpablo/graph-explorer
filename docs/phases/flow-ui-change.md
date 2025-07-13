## Change triggered by the UI.


```text
3b. [versionedFullGraphV <- fullGraphV]: 160.724 s,  at: 17:21:01.439Z
Versioned(ViewerGraph(ViewerGraphElements(VectorMap(a -> ViewerNode(a,Attributes(VectorMap(_gvid -> 0, label -> a, pos -> 27,18, height -> 0.5, width -> 0.75, fillcolor -> #bedbff, invisiblestyle -> false, boldstyle -> false, borderstyle -> solid, cornerstyle -> normal, fillstyle -> true)),None)),Map(),VectorMap(),Map(),Attributes(VectorMap(directed -> true)),Attributes(Map()),Attributes(Map()),Attributes(Map())),G,digraph,0),3,Graph)

4. [fullGraphV -> visibleGraph]: 0.017 s,  at: 17:21:01.456Z
ViewerGraph(ViewerGraphElements(VectorMap(a -> ViewerNode(a,Attributes(VectorMap(_gvid -> 0, label -> a, pos -> 27,18, height -> 0.5, width -> 0.75, fillcolor -> #bedbff, invisiblestyle -> false, boldstyle -> false, borderstyle -> solid, cornerstyle -> normal, fillstyle -> true)),None)),Map(),VectorMap(),Map(),Attributes(VectorMap(directed -> true)),Attributes(Map(sides -> 5, shape -> box)),Attributes(Map(dir -> both, arrowhead -> vee, arrowtail -> none)),Attributes(Map())),G,digraph,0)

5. [visibleGraph -> visibleDOT]: 0 s,  at: 17:21:01.456Z
digraph "G" {
  "a" [id="node:a", label="a", pos="27,18", height="0.5", width="0.75", fillcolor="#bedbff", style="filled"];
}

6. [visibleDOT -> SVG]: 0.002 s,  at: 17:21:01.459Z
ReactiveSvgElement(<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="-3.6 -40.4 62 44" class="graphviz">
<g id="graph0" class="graph" transform="scale(1 1) rotate(0) translate(4 40)">
<title>G</title>
<polygon fill="white" stroke="none" points="-4,4 -4,-40 58,-40 58,4 -4,4"/>
<!-- a -->
<g id="node:a" class="node">
<title>a</title>
<ellipse fill="#bedbff" stroke="black" cx="27" cy="-18" rx="27" ry="18"/>
<text xml:space="preserve" text-anchor="middle" x="27" y="-13.8" font-family="Times,serif" font-size="14.00">a</text>
</g>
</g>
<rect id="selection-rectangle"/></svg>)

2b. [versionedText <- versionedFullGraphV]: 0.012 s,  at: 17:21:01.471Z
Versioned(digraph "G" {
  "a" [id="node:a", label="a", pos="27,18", height="0.5", width="0.75", fillcolor="#bedbff", style="filled"];
},3,Graph)

1b. [sourceText <- versionedText]: 0 s,  at: 17:21:01.472Z
digraph "G" {
  "a" [id="node:a", label="a", pos="27,18", height="0.5", width="0.75", fillcolor="#bedbff", style="filled"];
}


```