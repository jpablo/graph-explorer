Right now the internal phases go something like this:

```text
sourceText <-> versionedText
versionedText <-> sourceAST
sourceAST <-> versionedFullGraphV
versionedFullGraphV <-> fullGraphV
fullGraphV --> visibleGraph
visibleGraph -> visibleAST -> visibleDOT
visibleDOT ~> SvgWithPositions
svgWithPositions ~> finalSVG
```



