Right now the internal phases go something like this:

```text
sourceText <-> versionedText
versionedText <-> versionedFullGraphV
versionedFullGraphV <-> fullGraphV
fullGraphV --> visibleGraph
visibleGraph -> visibleDOT
visibleDOT -> finalSVG
```



