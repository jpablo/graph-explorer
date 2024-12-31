
Problematic dot source
## Left

```dot
digraph "G" {
    graph [bgcolor="#f0dbdb"];
    node [
        shape="rectangle",
        style="filled",
        fillcolor="#ffcc00"
    ];
    subgraph cluster_2266e1ed {
        graph [label="2266e1ed"];
        "584fdc0b" [label="/api/sync-campaign-offers"];
        "03716d32" [
            label="/api/admin/ingest/attributes",
            shape="box3d",
            fontcolor="#d65757",
            fillcolor="#eec8c8"
        ];
    }
    subgraph cluster_7b53324a {
        graph [label=""];
        "a851cc96" [label="triggerAttributesDataIngestion"];
        "a4cc1585" [label="triggerCampaignOffersProcess"];
    }
    subgraph cluster_2bf314a3 {
        graph [label=""];
        "a27e53ee" [label="ingest"];
    }
    "584fdc0b" -> "a4cc1585" [id="1"];
    "03716d32" -> "a851cc96" [id="2"];
    "a4cc1585" -> "a27e53ee" [id="3"];
    "a851cc96" -> "a27e53ee" [id="4"];
}
```

The 3d box appears to the left in graphviz, but to the right in https://dreampuf.github.io/GraphvizOnline
and https://edotor.net/.

## Right

```dot
digraph "G" {
    graph [bgcolor="#f4ebeb"];
    node [
        shape="rectangle",
        style="filled",
        fillcolor="#ffcc00"
    ];
    subgraph cluster_2266e1ed {
        graph [label="2266e1ed"];
        "584fdc0b" [label="/api/sync-campaign-offers"];
        "03716d32" [
            label="/api/admin/ingest/attributes",
            shape="box3d",
            fontcolor="#d65757",
            fillcolor="#eec8c8"
        ];
    }
    subgraph cluster_7b53324a {
        graph [label=""];
        "a851cc96" [label="triggerAttributesDataIngestion"];
        "a4cc1585" [label="triggerCampaignOffersProcess"];
    }
    subgraph cluster_2bf314a3 {
        graph [label=""];
        "a27e53ee" [label="ingest"];
    }
    "584fdc0b" -> "a4cc1585" [id="1"];
    "03716d32" -> "a851cc96" [id="2"];
    "a4cc1585" -> "a27e53ee" [id="3"];
    "a851cc96" -> "a27e53ee" [id="4"];
}
```

The problem seems to happen in VizJS.
The same DOT source results in an SVG with clusters in different order.

