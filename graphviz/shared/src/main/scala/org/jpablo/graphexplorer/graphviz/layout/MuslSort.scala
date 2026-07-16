package org.jpablo.graphexplorer.graphviz.layout

/** musl libc `qsort` — smoothsort (src/stdlib/qsort.c, Valentin Ochs) —
  * transcribed 1:1 specialised to width-1 Int elements.
  *
  * WHY: the byte-exact ORACLE is viz-js = Graphviz compiled with
  * emscripten/musl, and gv sorts with plain `qsort` in tie-UNSTABLE
  * comparators (e.g. ns.c `TB_balance`'s `increasingrankcmpf`, rank only).
  * The equal-key PERMUTATION such a sort produces is implementation-defined
  * and feeds order-sensitive downstream walks (TB_balance's
  * least-populated-rank bookkeeping), so matching the oracle requires
  * musl's exact permutation — a stable sort is NOT equivalent.
  *
  * The `p[2]` bit-register is kept as two Longs (the C builds it from two
  * size_t words; the abstract algorithm — and hence the permutation — is
  * word-size independent for our array sizes). The C's AR_LEN wrap masking
  * is replaced by a comfortably-large chain buffer (chain depth is
  * O(log n · φ), far below 256 for any graph we rank).
  */
object MuslSort:

  /** Sort `a` in place by `cmp` with musl-qsort's exact permutation. */
  def sort(a: Array[Int], cmp: (Int, Int) => Int): Unit =
    val n = a.length
    if n == 0 then return

    inline def cmpAt(p: Int, q: Int): Int = cmp(a(p), a(q))

    // Precompute Leonardo numbers (width = 1): lp[i] = lp[i-2] + lp[i-1] + 1
    val lp = new Array[Long](96)
    lp(0) = 1L; lp(1) = 1L
    var li = 2
    var last = 0L
    while { last = lp(li - 2) + lp(li - 1) + 1L; lp(li) = last; last < n } do li += 1

    var p0 = 1L; var p1 = 0L
    var pshift = 1

    def shl(sh: Int): Unit =
      var s = sh
      if s >= 64 then
        s -= 64
        p1 = p0; p0 = 0L
        if s == 0 then return
      p1 = (p1 << s) | (p0 >>> (64 - s))
      p0 = p0 << s

    def shr(sh: Int): Unit =
      var s = sh
      if s >= 64 then
        s -= 64
        p0 = p1; p1 = 0L
        if s == 0 then return
      p0 = (p0 >>> s) | (p1 << (64 - s))
      p1 = p1 >>> s

    def pntz(): Int =
      if p0 != 1L then java.lang.Long.numberOfTrailingZeros(p0 - 1L)
      else if p1 != 0L then 64 + java.lang.Long.numberOfTrailingZeros(p1)
      else 0

    val ar = new Array[Int](512)

    def cycle(cnt: Int): Unit =
      if cnt >= 2 then
        val tmp = a(ar(0))
        var i = 0
        while i < cnt - 1 do { a(ar(i)) = a(ar(i + 1)); i += 1 }
        a(ar(cnt - 1)) = tmp

    def sift(head0: Int, pshift0: Int): Unit =
      var head = head0; var ps = pshift0
      ar(0) = head
      var i = 1
      var stop = false
      while ps > 1 && !stop do
        val rt = head - 1
        val lf = head - 1 - lp(ps - 2).toInt
        if cmpAt(ar(0), lf) >= 0 && cmpAt(ar(0), rt) >= 0 then stop = true
        else if cmpAt(lf, rt) >= 0 then { ar(i) = lf; i += 1; head = lf; ps -= 1 }
        else { ar(i) = rt; i += 1; head = rt; ps -= 2 }
      cycle(i)

    def trinkle(head0: Int, pp0: Long, pp1: Long, pshift0: Int, trusty0: Boolean): Unit =
      var head = head0; var ps = pshift0; var trusty = trusty0
      var q0 = pp0; var q1 = pp1
      ar(0) = head
      var i = 1
      var stop = false
      while (q0 != 1L || q1 != 0L) && !stop do
        val stepson = head - lp(ps).toInt
        if cmpAt(stepson, ar(0)) <= 0 then stop = true
        else if !trusty && ps > 1 && {
          val rt = head - 1
          val lf = head - 1 - lp(ps - 2).toInt
          cmpAt(rt, stepson) >= 0 || cmpAt(lf, stepson) >= 0
        } then stop = true
        else
          ar(i) = stepson; i += 1
          head = stepson
          val trail =
            if q0 != 1L then java.lang.Long.numberOfTrailingZeros(q0 - 1L)
            else if q1 != 0L then 64 + java.lang.Long.numberOfTrailingZeros(q1)
            else 0
          // shr(q, trail)
          var s = trail
          if s >= 64 then
            s -= 64; q0 = q1; q1 = 0L
          if s > 0 then
            q0 = (q0 >>> s) | (q1 << (64 - s)); q1 = q1 >>> s
          ps += trail
          trusty = false
      if !trusty then
        cycle(i)
        sift(head, ps)

    var head = 0
    val high = n - 1
    while head < high do
      if (p0 & 3L) == 3L then
        sift(head, pshift)
        shr(2); pshift += 2
      else
        if lp(pshift - 1) >= (high - head).toLong then trinkle(head, p0, p1, pshift, false)
        else sift(head, pshift)
        if pshift == 1 then { shl(1); pshift = 0 }
        else { shl(pshift - 1); pshift = 1 }
      p0 |= 1L
      head += 1
    trinkle(head, p0, p1, pshift, false)
    while pshift != 1 || p0 != 1L || p1 != 0L do
      if pshift <= 1 then
        val trail = pntz()
        shr(trail); pshift += trail
      else
        shl(2); pshift -= 2
        p0 ^= 7L
        shr(1)
        trinkle(head - lp(pshift).toInt - 1, p0, p1, pshift + 1, true)
        shl(1)
        p0 |= 1L
        trinkle(head - 1, p0, p1, pshift, true)
      head -= 1

end MuslSort
