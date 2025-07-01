package org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.graphviz.vizjs.simplegraph.SimpleGraph
import org.jpablo.graphexplorer.viewer.formats.dot.ast.AttrValue
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Cluster
import org.jpablo.graphexplorer.viewer.models.GroupId
import upickle.default.*

class SimpleGraphConverterClusterAttributeSpec extends FunSuite:

  test("SimpleGraphConverter should set cluster=false when cluster attribute is missing in SimpleGraph cluster") {
    val groupId = GroupId("G0")
    // Create a SimpleGraph with a cluster that doesn't have the cluster attribute
    val subgraphJson = """{
      "name": "MainGraph",
      "directed": true,
      "objects": [
        { "_gvid": 0, "name": "subNode1", "label": "subNode1" },
        { "_gvid": 1, "name": "subNode2", "label": "subNode2" },
        { "_gvid": 2, "name": "G0", "nodes": [0, 1], "label": "G0" }
      ],
      "edges": [
        { "_gvid": 0, "tail": 0, "head": 1, "id": "subNode1->subNode2/1" }
      ]
    }"""

    val graph = read[SimpleGraph](subgraphJson)

    val elements = simplegraph.toViewerGraphElements(graph)

    // Verify that the group has been created
    assertEquals(elements.groups.size, 1)
    assert(elements.groups.contains(groupId))

    // Check that the cluster attribute is explicitly set to "false"
    val group       = elements.groups(groupId)
    val clusterAttr = group.attributes.get(Cluster)
    assertEquals(clusterAttr, Some(AttrValue("false")), "Cluster attribute should be set to 'false' when missing in SimpleGraph")
  }

  test("SimpleGraphConverter should preserve existing cluster=true attribute") {
    val groupId = GroupId("G0")
    // Create a SimpleGraph with a cluster that explicitly has cluster=true
    val subgraphJson = """{
      "name": "MainGraph",
      "directed": true,
      "objects": [
        { "_gvid": 0, "name": "subNode1", "label": "subNode1" },
        { "_gvid": 1, "name": "subNode2", "label": "subNode2" },
        { "_gvid": 2, "name": "G0", "nodes": [0, 1], "label": "G0", "cluster": "true" }
      ],
      "edges": [
        { "_gvid": 0, "tail": 0, "head": 1, "id": "subNode1->subNode2/1" }
      ]
    }"""

    val graph    = read[SimpleGraph](subgraphJson)
    val elements = simplegraph.toViewerGraphElements(graph)

    // Verify that the group has been created
    assertEquals(elements.groups.size, 1)
    assert(elements.groups.contains(groupId))

    // Check that the cluster attribute is preserved as "true"
    val group = elements.groups(groupId)

    val clusterAttr = group.attributes.get(Cluster)
    assertEquals(clusterAttr, Some(AttrValue("true")), "Cluster attribute should be preserved as 'true' when explicitly set")
  }

  test("SimpleGraphConverter should preserve existing cluster=false attribute") {
    val groupId = GroupId("G0")
    // Create a SimpleGraph with a cluster that explicitly has cluster=false
    val subgraphJson = """{
      "name": "MainGraph",
      "directed": true,
      "objects": [
        { "_gvid": 0, "name": "subNode1", "label": "subNode1" },
        { "_gvid": 1, "name": "subNode2", "label": "subNode2" },
        { "_gvid": 2, "name": "G0", "nodes": [0, 1], "label": "G0", "cluster": "false" }
      ],
      "edges": [
        { "_gvid": 0, "tail": 0, "head": 1, "id": "subNode1->subNode2/1" }
      ]
    }"""

    val graph    = read[SimpleGraph](subgraphJson)
    val elements = simplegraph.toViewerGraphElements(graph)

    // Verify that the group has been created
    assertEquals(elements.groups.size, 1)
    assert(elements.groups.contains(groupId))

    // Check that the cluster attribute is preserved as "false"
    val group       = elements.groups(groupId)
    val clusterAttr = group.attributes.get(Cluster)
    assertEquals(clusterAttr, Some(AttrValue("false")), "Cluster attribute should be preserved as 'false' when explicitly set")
  }

  test("SimpleGraphConverter should set cluster=true when a subgraph name starts with `cluster`") {
    val groupId = GroupId("0")
    // Create a SimpleGraph with a cluster that doesn't have the cluster attribute but name starts with "cluster"
    val subgraphJson = """{
      "name": "MainGraph",
      "directed": true,
      "objects": [
        { "_gvid": 0, "name": "subNode1", "label": "subNode1" },
        { "_gvid": 1, "name": "subNode2", "label": "subNode2" },
        { "_gvid": 2, "name": "cluster0", "nodes": [0, 1], "label": "cluster0" }
      ],
      "edges": [
        { "_gvid": 0, "tail": 0, "head": 1, "id": "subNode1->subNode2/1" }
      ]
    }"""

    val graph = read[SimpleGraph](subgraphJson)

    val elements = simplegraph.toViewerGraphElements(graph)

    // Verify that the group has been created
    assertEquals(elements.groups.size, 1)
    assert(elements.groups.contains(groupId))

    // Check that the cluster attribute is explicitly set to "true"
    val group       = elements.groups(groupId)
    val clusterAttr = group.attributes.get(Cluster)
    assertEquals(clusterAttr, Some(AttrValue("true")), "Cluster attribute should be set to 'true' when subgraph name starts with 'cluster'")
  }
