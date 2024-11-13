package org.jpablo.graphexplorer.viewer.formats.dot.ast

case class GraphElementAttributes(
    rankdir:   Option[String] = None,           // TB, LR, BT, RL
    label:     Option[String] = None,
    size:      Option[(Double, Double)] = None, // width, height in inches
    splines:   Option[String] = None,           // line, spline, polyline, ortho
    bgcolor:   Option[String] = None,
    margin:    Option[(Double, Double)] = None, // x, y margins in inches
    fontname:  Option[String] = None,
    fontsize:  Option[Double] = None,
    fontcolor: Option[String] = None,
    overlap:   Option[String] = None            // false, scale, compress, etc.
):

  def toMap = Map(
    "rankdir"   -> rankdir.map(v => singleAttr("rankdir", v)),
    "label"     -> label.map(v => singleAttr("label", v)),
    "size"      -> size.map(v => singleAttr("size", s"${v._1},${v._2}")),
    "splines"   -> splines.map(v => singleAttr("splines", v)),
    "bgcolor"   -> bgcolor.map(v => singleAttr("bgcolor", v)),
    "margin"    -> margin.map(v => singleAttr("margin", s"${v._1},${v._2}")),
    "fontname"  -> fontname.map(v => singleAttr("fontname", v)),
    "fontsize"  -> fontsize.map(v => singleAttr("fontsize", v.toString)),
    "fontcolor" -> fontcolor.map(v => singleAttr("fontcolor", v)),
    "overlap"   -> overlap.map(v => singleAttr("overlap", v))
  ).collect { case (k, Some(v)) => k -> v }

def singleAttr(name: String, value: String) =
  AttrStmt("graph", List(Attr(name, value)))
