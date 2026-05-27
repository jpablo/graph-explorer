package org.jpablo.graphexplorer.graphviz.units

import scala.annotation.targetName

/** Length units used throughout the `dot` layout port.
  *
  * Graphviz natively measures everything in **points** (1 pt = 1/72 in). DOT
  * attributes `width`/`height` (and the `width`/`height` json fields) are in
  * **inches**; the conversion `× 72` is the single most common silent-bug
  * site in the codebase, so [[Length.Pt]] and [[Length.In]] are opaque types
  * that never implicitly convert.
  *
  * Arithmetic stays scalar — `Pt + Pt = Pt`, `Pt * Double = Pt` — so layout
  * code reads as it did before, but mixing units (or treating a width-in-
  * inches as a coordinate-in-points) is now a compile error rather than a
  * 72× layout glitch. `@targetName` is required because opaque `Pt`/`In`
  * both erase to `Double`, so their arithmetic extensions would otherwise
  * collide after erasure (T03 gotcha).
  */
object Length:

  opaque type Pt = Double
  opaque type In = Double

  private val PtPerIn = 72.0

  object Pt:
    inline def apply(d: Double): Pt = d
    val Zero: Pt = 0.0
    val MaxValue: Pt = Double.MaxValue
    val MinValue: Pt = Double.MinValue
    given CanEqual[Pt, Pt] = CanEqual.derived

  object In:
    inline def apply(d: Double): In = d
    val Zero: In = 0.0
    given CanEqual[In, In] = CanEqual.derived

  extension (x: Pt)
    @targetName("ptValue") inline def value: Double = x
    @targetName("addPt")  inline def +(y: Pt): Pt = x + y
    @targetName("subPt")  inline def -(y: Pt): Pt = x - y
    @targetName("mulPt")  inline def *(k: Double): Pt = x * k
    @targetName("divPt")  inline def /(k: Double): Pt = x / k
    @targetName("negPt")  inline def unary_- : Pt = -x
    inline def toIn: In = x / PtPerIn

  extension (x: In)
    @targetName("inValue") inline def value: Double = x
    @targetName("addIn") inline def +(y: In): In = x + y
    @targetName("subIn") inline def -(y: In): In = x - y
    @targetName("mulIn") inline def *(k: Double): In = x * k
    @targetName("divIn") inline def /(k: Double): In = x / k
    inline def toPt: Pt = x * PtPerIn

  /** `math.min` / `math.max` / `math.abs` over [[Pt]]. */
  object PtOps:
    inline def min(a: Pt, b: Pt): Pt = math.min(a, b)
    inline def max(a: Pt, b: Pt): Pt = math.max(a, b)
    inline def abs(a: Pt): Pt = math.abs(a)

end Length
