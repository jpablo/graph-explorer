package org.jpablo.graphexplorer.viewer.state

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.utils.SvgPoint

/** The pan draws the trackpad input at the trackpad times, not at the times
  * the page gets it.
  *
  * A record of real trackpad pans in the macOS desktop window (WebKit, 120 Hz)
  * showed this: each wheel delta is the motion since the previous trackpad
  * sample, but the page gets the events 1 ms or 8 ms after their timestamps, in
  * turn. A pan that adds each delta in the frame that gets it thus moves by a
  * small step and then by a large step. The deltas are whole pixels, and the
  * times are whole milliseconds.
  *
  * The synthetic input of these tests has the same form: a trackpad sample
  * every 8.33 ms, whole-pixel deltas, an arrival 1 ms or 8 ms after the
  * timestamp in turn, and frames every 8.33 ms at whole milliseconds.
  *
  * The checks throw AssertionError, not munit's `assert`: through the Scala.js
  * test bridge and the sbt client, a munit FailException arrives with no message.
  */
class PanResamplerSpec extends FunSuite:

  private def check(condition: Boolean, message: => String): Unit =
    if !condition then throw AssertionError(message)

  /** The speed of the fingers in the synthetic pans, in units per millisecond. */
  private val Speed = 0.5

  /** A steady pan to the right of `count` trackpad samples, from `startMs`. */
  private def steadyPan(count: Int, startMs: Double = 0.0): Vector[PanSample] =
    val (samples, _, _) =
      (1 to count).foldLeft((Vector.empty[PanSample], 0.0, startMs)):
        case ((acc, carry, prevStamp), k) =>
          val stamp   = math.round(startMs + k * 8.333).toDouble
          val exact   = Speed * (stamp - prevStamp) + carry
          val delta   = math.round(exact).toDouble
          val arrival = stamp + (if k % 2 == 1 then 1.0 else 8.0)
          (acc :+ PanSample(stamp, arrival, SvgPoint(delta, 0.0)), exact - delta, stamp)
    samples

  private def frames(fromMs: Double, count: Int): Vector[Double] =
    Vector.tabulate(count)(k => math.round(fromMs + k * 8.333).toDouble)

  private def feed(samples: Vector[PanSample]): PanResampler =
    samples.foldLeft(PanResampler.empty(SvgPoint.origin))(_.add(_))

  /** The resampler as the pan loop uses it: each sample is added when it arrives. */
  private def drawn(samples: Vector[PanSample], at: Vector[Double]): Vector[Double] =
    at.map(t => feed(samples.filter(_.arrivalMs <= t)).at(t).x)

  test("a steady pan moves at a steady speed, also when the events arrive at irregular times"):
    val samples = steadyPan(120)
    val ts      = frames(4.0, 160)
    val xs      = drawn(samples, ts)
    // The speed in each frame, in the middle of the pan.
    val speeds =
      (30 until 110).map(i => (xs(i + 1) - xs(i)) / (ts(i + 1) - ts(i)))
    val mean  = speeds.sum / speeds.size
    val worst = speeds.map(s => math.abs(s / mean - 1)).max
    check(math.abs(mean - Speed) < 0.02, f"the mean speed is $mean%.3f, not $Speed")
    check(worst <= 0.25, f"the speed of one frame is ${worst * 100}%.0f%% away from the mean")

  test("a steady pan trails the fingers by about the delay, and less than the old 60 Hz filter (20 ms)"):
    val samples = steadyPan(120)
    val ts      = frames(4.0, 160)
    val xs      = drawn(samples, ts)
    val lags    = (40 until 110).map(i => ts(i) - xs(i) / Speed)
    val lag     = lags.sum / lags.size
    check(lag >= 10.0 && lag <= 20.0, f"the pan trails the fingers by $lag%.1f ms")

  test("the drawn pan never moves back and never passes the total input"):
    val samples = steadyPan(60)
    val ts      = frames(0.0, 120)
    val xs      = drawn(samples, ts)
    val total   = samples.map(_.delta.x).sum
    xs.zip(xs.drop(1)).foreach: (a, b) =>
      check(b >= a - 1e-9, f"the pan moved back from $a%.2f to $b%.2f")
    xs.foreach(x => check(x <= total + 1e-9, f"the pan is at $x%.2f, past the total $total%.2f"))

  test("after the last input, the pan settles exactly on the total"):
    val samples = steadyPan(40)
    val r       = feed(samples)
    val last    = samples.last
    check(!r.settledAt(last.arrivalMs), "the pan is not settled at the arrival of the last input")
    val end = last.stampMs + PanResampler.DelayMs + PanResampler.BoxMs
    check(r.settledAt(end), s"the pan is not settled at $end ms")
    check(r.at(end).toTuple == r.total.toTuple, s"the pan at $end ms is ${r.at(end)}, not the total ${r.total}")

  test("a sample with a bad timestamp uses its arrival time"):
    // A timestamp after the arrival, or long before it, is not a trackpad time.
    val bad = Vector(
      PanSample(0.0, 1000.0, SvgPoint(4.0, 0.0)),    // long before the arrival
      PanSample(5000.0, 1008.0, SvgPoint(4.0, 0.0)), // after the arrival
      PanSample(1012.0, 1016.0, SvgPoint(4.0, 0.0))
    )
    val ts = frames(1000.0, 12)
    val xs = drawn(bad, ts)
    xs.zip(xs.drop(1)).foreach: (a, b) =>
      check(b >= a - 1e-9 && b - a <= 8.0 + 1e-9, f"the pan jumped from $a%.2f to $b%.2f")
    check(xs.last == 12.0, s"the pan ends at ${xs.last}, not at 12")

  test("a new pan after a pause starts from rest, with no jump"):
    val first  = steadyPan(20)
    val second = steadyPan(20, startMs = 2000.0)
    val ts     = frames(1990.0, 30)
    val xs     = drawn(first ++ second, ts)
    val start  = first.map(_.delta.x).sum
    val steps  = xs.zip(xs.drop(1)).map((a, b) => b - a)
    check(math.abs(xs.head - start) < 1e-9, s"before the second pan, the pan is at ${xs.head}, not at $start")
    // A steady step is about 4 units. The first steps of the new pan grow to it.
    check(steps.forall(_ <= 6.0), s"a step of the new pan is too large: ${steps.map(s => f"$s%.1f").mkString(" ")}")
