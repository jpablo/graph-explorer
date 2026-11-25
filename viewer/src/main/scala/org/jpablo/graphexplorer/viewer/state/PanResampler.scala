package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.utils.SvgPoint

import scala.collection.mutable.ArrayBuffer

/** One pan input: a delta, the trackpad time of the delta, and the time the
  * page got it. All times are in milliseconds on the clock of
  * `performance.now()`. */
final case class PanSample(stampMs: Double, arrivalMs: Double, delta: SvgPoint) derives CanEqual

/** The pan input, resampled at the frame times by the trackpad timestamps.
  *
  * Each wheel delta is the motion of the fingers since the previous trackpad
  * sample. The page gets the events at irregular times, but their timestamps
  * are regular. The resampler thus draws the input as a function of the
  * timestamps, not of the arrival times.
  *
  * The input position is `base` plus the deltas of the samples. It is a
  * piecewise-linear function of the sample time. The frame at `t` draws this
  * function at `t - DelayMs`. The delay is larger than the time from a
  * timestamp to its arrival. Thus the frame usually has the samples on the two
  * sides of the time that it draws, and it interpolates between them.
  *
  * The samples keep their effective times in `stampMs` (see [[add]]). The
  * effective times do not decrease.
  *
  * @param base
  *   the input position before the first sample.
  * @param samples
  *   the samples that can still change the drawn pan, in the order of arrival.
  */
final case class PanResampler(base: SvgPoint, samples: Vector[PanSample]) derives CanEqual:
  import PanResampler.*

  /** Add one input.
    *
    * The sample gets its effective time. That time is the timestamp, but a bad
    * timestamp changes to the arrival time. A timestamp is bad when it is
    * after the arrival, or more than [[MaxStampAgeMs]] before it. A synthetic
    * event can have such a timestamp. An effective time that is earlier than
    * the effective time of the previous sample changes to that time.
    *
    * The function also folds old samples into `base` (see [[DropSlackMs]]).
    * Thus the vector stays short during a long pan.
    */
  def add(sample: PanSample): PanResampler =
    val stampIsGood = sample.stampMs <= sample.arrivalMs && sample.stampMs >= sample.arrivalMs - MaxStampAgeMs
    val raw         = if stampIsGood then sample.stampMs else sample.arrivalMs
    val effective   = samples.lastOption.fold(raw)(prev => raw max prev.stampMs)
    PanResampler(base, samples :+ sample.copy(stampMs = effective)).dropBefore(sample.arrivalMs - DropSlackMs)

  /** Fold into `base` the samples that the frames at `frameMs` and later
    * cannot see.
    *
    * A frame sees the input from its window start (`frameMs - DelayMs -
    * BoxMs / 2`) on. Before the window start, it needs only the last sample
    * at or before that time. That sample stays as an anchor: a sample with
    * its time and no delta. The folded deltas go into `base`.
    */
  private def dropBefore(frameMs: Double): PanResampler =
    val windowStart = frameMs - DelayMs - BoxMs / 2
    val last        = samples.lastIndexWhere(_.stampMs <= windowStart)
    if last < 1 then this
    else
      val (old, kept) = samples.splitAt(last + 1)
      val anchor      = old.last.copy(delta = SvgPoint.origin)
      PanResampler(old.foldLeft(base)(plus), anchor +: kept)

  /** The pan to draw in the frame at `frameMs`.
    *
    * Only the samples that arrived at `frameMs` or before count. The result is
    * exactly [[total]] when all the samples arrived and the window start
    * (`frameMs - DelayMs - BoxMs / 2`) is at or after the last effective time.
    */
  def at(frameMs: Double): SvgPoint =
    val (times, points) = knots(frameMs)
    if times.isEmpty then base
    else
      val time = frameMs - DelayMs
      val from = time - BoxMs / 2
      val to   = time + BoxMs / 2
      if from >= times.last then points.last
      else if to <= times.head then points.head
      else if BoxMs <= 0 then valueAt(times, points, time)
      else average(times, points, from, to)

  /** The pan when all the input is drawn. */
  def total: SvgPoint = samples.foldLeft(base)(plus)

  /** True when the frame at `frameMs` draws [[total]], so the pan loop can stop. */
  def settledAt(frameMs: Double): Boolean = at(frameMs).toTuple == total.toTuple

  /** The corners of the input function for the frame at `frameMs`: the times
    * and the input positions. The times increase strictly.
    *
    * Each sample gives a corner at its effective time. Samples with the same
    * time merge into one corner. A sample after a pause (more than
    * [[PauseMs]] after the previous sample, or the first sample) gets one more
    * corner: the previous position, [[SamplePeriodMs]] before the sample.
    * Thus a pan starts from rest. It does not glide from the end of the
    * previous pan.
    */
  private def knots(frameMs: Double): (ArrayBuffer[Double], ArrayBuffer[SvgPoint]) =
    val times  = ArrayBuffer.empty[Double]
    val points = ArrayBuffer.empty[SvgPoint]
    var pos    = base
    for s <- samples if s.arrivalMs <= frameMs do
      // After the first sample, the last corner is the corner of the previous sample.
      if times.isEmpty || s.stampMs - times.last > PauseMs then
        times += s.stampMs - SamplePeriodMs
        points += pos
      pos = plus(pos, s)
      if times.last == s.stampMs then points(points.size - 1) = pos
      else
        times += s.stampMs
        points += pos
    (times, points)

object PanResampler:
  /** The time between a trackpad sample and the frame that draws it.
    *
    * The page gets the events up to about 8 ms after their timestamps. A
    * delay of two trackpad periods (16 ms at 120 Hz) thus lets a frame see the
    * sample after the time that it draws, also for a late event.
    */
  val DelayMs = 16.0

  /** The width of the time window that the drawn pan averages. Zero draws the
    * input function at one time, with no average.
    *
    * The value is 16 ms. The real trackpad deltas have noise: whole pixels,
    * and a hand that does not move at a steady speed. A replay of six recorded
    * pans (macOS, WebKit, 120 Hz) gave these numbers for the judder (the wobble
    * of the drawn path around its smooth path) and the error from the smooth
    * path of the fingers:
    *
    *   - no box: 1.12 px judder, 0.84 px error
    *   - 8 ms: 1.03 px, 0.69 px
    *   - 16 ms: 0.96 px, 0.54 px
    *   - the old 60 Hz filter (20 ms): 0.88 px, 1.07 px, and a lag of 20 ms
    *
    * The lag is 16 ms for each box width. On the clean synthetic input of
    * PanResamplerSpec, a box makes the steps a little less equal (16% at
    * 16 ms, 7% with no box), because that input has no noise to remove. A box
    * also delays the stop of a pan by half its width.
    */
  val BoxMs = 16.0

  /** The time of a trackpad sample (120 Hz). The first sample of a pan
    * starts to move the drawn pan this time before its timestamp. The delta
    * of a sample is the motion over one such period.
    */
  val SamplePeriodMs: Double = 1000.0 / 120

  /** A gap between two samples that is longer than this value is a pause.
    * The pan after it starts from rest (see [[SamplePeriodMs]]). A shorter
    * gap is a slow motion or a missing sample. The resampler then spreads the
    * delta over the whole gap.
    */
  val PauseMs: Double = 3 * SamplePeriodMs

  /** A timestamp that is more than this value before the arrival is bad. */
  val MaxStampAgeMs = 100.0

  /** [[PanResampler.add]] keeps the samples that a frame can need, if the frame
    * is not more than this value before the arrival of the newest sample. A
    * frame time can be a little earlier than the arrival of an event that the
    * page got before the frame callback.
    */
  val DropSlackMs = 100.0

  def empty(base: SvgPoint): PanResampler = PanResampler(base, Vector.empty)

  private def plus(p: SvgPoint, s: PanSample): SvgPoint = SvgPoint(p.x + s.delta.x, p.y + s.delta.y)

  /** The input position at `time`, by linear interpolation between the
    * corners. Before the first corner, it is the first position. After the
    * last corner, it is the last position. */
  private def valueAt(times: collection.IndexedSeq[Double], points: collection.IndexedSeq[SvgPoint], time: Double): SvgPoint =
    val i = times.indexWhere(_ >= time)
    if i < 0 then points.last
    else if i == 0 then points.head
    else lerp(times(i - 1), points(i - 1), times(i), points(i), time)

  private def lerp(t0: Double, p0: SvgPoint, t1: Double, p1: SvgPoint, time: Double): SvgPoint =
    val f = (time - t0) / (t1 - t0)
    SvgPoint(p0.x + (p1.x - p0.x) * f, p0.y + (p1.y - p0.y) * f)

  /** The mean of the input function from `from` to `to`. The function adds the
    * exact area of each linear piece in the window. */
  private def average(times: collection.IndexedSeq[Double], points: collection.IndexedSeq[SvgPoint], from: Double, to: Double): SvgPoint =
    var x = 0.0
    var y = 0.0
    def area(lo: Double, hi: Double): Unit =
      if hi > lo then
        val a = valueAt(times, points, lo)
        val b = valueAt(times, points, hi)
        x += (a.x + b.x) / 2 * (hi - lo)
        y += (a.y + b.y) / 2 * (hi - lo)
    // The pieces: before the first corner, between the corners, after the last corner.
    val edges = (from +: times.filter(t => t > from && t < to)) :+ to
    edges.zip(edges.drop(1)).foreach(area)
    SvgPoint(x / (to - from), y / (to - from))
