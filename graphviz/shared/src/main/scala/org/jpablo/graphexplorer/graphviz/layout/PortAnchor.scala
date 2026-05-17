package org.jpablo.graphexplorer.graphviz.layout

import org.jpablo.graphexplorer.graphviz.dotlang.Compass
import org.jpablo.graphexplorer.graphviz.model.{RGraph, RNode}

/** Port → anchor resolution: `lib/common/shapes.c` `record_port` +
  * `compassPort` (gv 13.0.1), scoped to record field ports (TB, no
  * rankdir flip — the rotation `cwrotatepf(p, 90·rankdir)` is identity).
  *
  * `record_port` maps the port *name* to its field via `map_rec_port`
  * (handled by `RecordLabel.fieldBox`), then `compassPort` turns the field
  * box + compass into a node-local point plus routing flags:
  *
  *  - no compass / `_` / `c`: `p` = box centre, `clip=true` (the spline is
  *    clipped to the field box — visible endpoint = box boundary on the
  *    exit side, resolved by the router);
  *  - `n`/`s`/`e`/`w`: side mid-point, `constrained` with a fixed tangent
  *    `theta`, `clip=false` (that point *is* the endpoint);
  *  - `ne`/`se`/`sw`/`nw`: the corresponding box corner.
  *
  * `p` is node-local (centre origin, y-up) — add `ND_coord(n)` for absolute.
  */
object PortAnchor:

  final case class Anchor(
      x: Double, y: Double,        // node-local (centre origin, y-up)
      clip: Boolean,               // spline clipped to the field box
      constrained: Boolean,        // fixed tangent at the endpoint
      theta: Double                // constrained tangent (radians)
  )

  private val HalfPi = math.Pi / 2.0

  /** compassPort over a node-local field box `(llx,lly,urx,ury)` (y-up). */
  def fromBox(llx: Double, lly: Double, urx: Double, ury: Double, c: Option[Compass]): Anchor =
    val cx = (llx + urx) / 2.0
    val cy = (lly + ury) / 2.0
    import Compass.*
    c match
      case None | Some(Underscore) | Some(C) =>
        Anchor(cx, cy, clip = true, constrained = false, 0.0)
      case Some(S)  => Anchor(cx, lly, clip = false, constrained = true, -HalfPi)
      case Some(N)  => Anchor(cx, ury, clip = false, constrained = true, HalfPi)
      case Some(E)  => Anchor(urx, cy, clip = false, constrained = true, 0.0)
      case Some(W)  => Anchor(llx, cy, clip = false, constrained = true, math.Pi)
      case Some(SE) => Anchor(urx, lly, clip = false, constrained = true, -HalfPi / 2)
      case Some(SW) => Anchor(llx, lly, clip = false, constrained = true, -3 * HalfPi / 2)
      case Some(NE) => Anchor(urx, ury, clip = false, constrained = true, HalfPi / 2)
      case Some(NW) => Anchor(llx, ury, clip = false, constrained = true, 3 * HalfPi / 2)

  /** Resolve `node:port[:compass]` to a node-local anchor, or `None` if the
    * node is not a record / the port name is unknown. */
  def resolve(n: RNode, g: RGraph, portName: Option[String], compass: Option[Compass]): Option[Anchor] =
    portName match
      case None => None // no field port (whole-node endpoint = existing path)
      case Some(name) =>
        for
          root <- NodeSize.recordLayout(n, g)
          box  <- RecordLabel.fieldBox(root, name)
        yield fromBox(box._1, box._2, box._3, box._4, compass)

end PortAnchor
