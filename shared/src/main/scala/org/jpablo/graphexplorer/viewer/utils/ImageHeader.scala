package org.jpablo.graphexplorer.viewer.utils

/** The natural size of an image, from the first bytes of its data.
  *
  * Graphviz cannot read an image, so the renderer must give it the size of each
  * image (see `DotImages`). A `data:` URI holds the bytes of the image, and the
  * header of the bytes holds the size. This object reads PNG, GIF, JPEG, WebP,
  * BMP and SVG.
  */
object ImageHeader:

  final case class Size(width: Int, height: Int) derives CanEqual

  /** A `data:` URI: the media type, the optional `;base64` mark, and the data. */
  private val DataUri = """(?s)data:([^,;]*)((?:;[^,;]*)*),(.*)""".r

  /** The JPEG size can come after a large EXIF block. The read stops after this many bytes. */
  private val JpegScanLimit = 256 * 1024

  /** The size of the image in the `data:` URI `uri`, or `None` when the data is not an image of a known type. */
  def ofDataUri(uri: String): Option[Size] =
    uri match
      case DataUri(mediaType, params, data) =>
        val isBase64 = params.split(';').exists(_.trim.equalsIgnoreCase("base64"))
        if mediaType.trim.equalsIgnoreCase("image/svg+xml") then
          val text =
            if isBase64 then Some(new String(Base64Prefix.decode(data, Int.MaxValue), "UTF-8"))
            else PercentDecoding.decode(data)
          text.flatMap(ofSvg)
        else if isBase64 then
          // Most headers are in the first 32 bytes. A JPEG needs more.
          val head = Base64Prefix.decode(data, 32)
          ofBytes(head).orElse(Option.when(isJpeg(head))(Base64Prefix.decode(data, JpegScanLimit)).flatMap(jpeg))
        else None
      case _ => None

  /** The size of the image that starts with the bytes `head`: a bitmap image, or else an SVG text. The desktop gives
    * the start of an image file next to a diagram (`local_image` in `main.rs`).
    */
  def ofHead(head: Array[Byte]): Option[Size] =
    ofBytes(head).orElse(ofSvg(new String(head, "UTF-8")))

  /** The size of the image in `bytes`, or `None` when the bytes are not an image of a known type. */
  def ofBytes(bytes: Array[Byte]): Option[Size] =
    png(bytes).orElse(gif(bytes)).orElse(jpeg(bytes)).orElse(webp(bytes)).orElse(bmp(bytes))

  private def u8(b: Array[Byte], i: Int): Int = b(i) & 0xff

  private def be16(b: Array[Byte], i: Int): Int = (u8(b, i) << 8) | u8(b, i + 1)
  private def le16(b: Array[Byte], i: Int): Int = u8(b, i) | (u8(b, i + 1) << 8)
  private def be32(b: Array[Byte], i: Int): Long =
    (u8(b, i).toLong << 24) | (u8(b, i + 1) << 16) | (u8(b, i + 2) << 8) | u8(b, i + 3)
  private def le32(b: Array[Byte], i: Int): Long =
    u8(b, i) | (u8(b, i + 1) << 8) | (u8(b, i + 2) << 16) | (u8(b, i + 3).toLong << 24)

  private def startsWith(b: Array[Byte], at: Int, ascii: String): Boolean =
    b.length >= at + ascii.length && ascii.indices.forall(i => b(at + i) == ascii.charAt(i).toByte)

  private def sized(width: Long, height: Long): Option[Size] =
    Option.when(width > 0 && height > 0 && width <= Int.MaxValue && height <= Int.MaxValue)(Size(width.toInt, height.toInt))

  /** PNG: the IHDR chunk is first, with the width and the height at bytes 16 and 20. */
  private def png(b: Array[Byte]): Option[Size] =
    val signature = b.length >= 24 && u8(b, 0) == 0x89 && startsWith(b, 1, "PNG") && startsWith(b, 12, "IHDR")
    if signature then sized(be32(b, 16), be32(b, 20)) else None

  /** GIF: the logical screen size is at bytes 6 and 8, little-endian. */
  private def gif(b: Array[Byte]): Option[Size] =
    if b.length >= 10 && (startsWith(b, 0, "GIF87a") || startsWith(b, 0, "GIF89a")) then sized(le16(b, 6), le16(b, 8))
    else None

  private def isJpeg(b: Array[Byte]): Boolean = b.length >= 3 && u8(b, 0) == 0xff && u8(b, 1) == 0xd8 && u8(b, 2) == 0xff

  /** JPEG: the first start-of-frame segment (SOF0 to SOF15, not DHT, JPG or DAC) has the height and the width. */
  private def jpeg(b: Array[Byte]): Option[Size] =
    if !isJpeg(b) then None
    else
      var i      = 2
      var result = Option.empty[Size]
      var done   = false
      while !done && i + 9 < b.length do
        if u8(b, i) != 0xff then done = true
        else
          val marker = u8(b, i + 1)
          if marker == 0xff then i += 1 // fill byte
          else if marker == 0xd8 || marker == 0x01 || (marker >= 0xd0 && marker <= 0xd7) then i += 2
          else
            val length = be16(b, i + 2)
            val isFrame = marker >= 0xc0 && marker <= 0xcf && marker != 0xc4 && marker != 0xc8 && marker != 0xcc
            if isFrame then
              result = sized(be16(b, i + 7), be16(b, i + 5))
              done = true
            else if marker == 0xda || marker == 0xd9 || length < 2 then done = true
            else i += 2 + length
      result

  /** WebP: a RIFF file with a VP8, VP8L or VP8X chunk. */
  private def webp(b: Array[Byte]): Option[Size] =
    if !(startsWith(b, 0, "RIFF") && startsWith(b, 8, "WEBP") && b.length >= 30) then None
    else if startsWith(b, 12, "VP8 ") then sized(le16(b, 26) & 0x3fff, le16(b, 28) & 0x3fff)
    else if startsWith(b, 12, "VP8L") then
      val bits = le32(b, 21)
      sized((bits & 0x3fff) + 1, ((bits >> 14) & 0x3fff) + 1)
    else if startsWith(b, 12, "VP8X") then
      val w = u8(b, 24) | (u8(b, 25) << 8) | (u8(b, 26) << 16)
      val h = u8(b, 27) | (u8(b, 28) << 8) | (u8(b, 29) << 16)
      sized(w.toLong + 1, h.toLong + 1)
    else None

  /** BMP: the width and the height are at bytes 18 and 22. A negative height means a top-down image. */
  private def bmp(b: Array[Byte]): Option[Size] =
    if b.length >= 26 && startsWith(b, 0, "BM") then sized(le32(b, 18).toInt.toLong.abs, le32(b, 22).toInt.toLong.abs)
    else None

  private val SvgTag     = """(?is)<svg\b([^>]*)>""".r
  private val NumberUnit = """^\s*([0-9]*\.?[0-9]+)\s*(px)?\s*$""".r

  private def svgAttr(attrs: String, name: String): Option[String] =
    s"""(?is)(?:^|\\s)$name\\s*=\\s*(?:"([^"]*)"|'([^']*)')""".r
      .findFirstMatchIn(attrs)
      .map(m => Option(m.group(1)).getOrElse(m.group(2)))

  /** SVG: the `width` and `height` of the root element in pixels, or else the size of its `viewBox`. */
  private def ofSvg(text: String): Option[Size] =
    SvgTag.findFirstMatchIn(text).flatMap: m =>
      val attrs = m.group(1)
      def px(name: String): Option[Double] =
        svgAttr(attrs, name).collect { case NumberUnit(n, _) => n.toDouble }
      val fromViewBox =
        svgAttr(attrs, "viewBox")
          .map(_.trim.split("[\\s,]+").toList.flatMap(_.toDoubleOption))
          .collect { case List(_, _, w, h) => (w, h) }
      val (w, h) = (px("width"), px("height")) match
        case (Some(w), Some(h)) => (Some(w), Some(h))
        case (w, h) =>
          fromViewBox match
            case Some((vw, vh)) if vw > 0 && vh > 0 =>
              // One given side and the aspect ratio of the view box give the other side.
              (w.orElse(h.map(_ * vw / vh)).orElse(Some(vw)), h.orElse(w.map(_ * vh / vw)).orElse(Some(vh)))
            case _ => (w, h)
      for
        width  <- w
        height <- h
        size   <- sized(math.round(width), math.round(height))
      yield size

  /** Decodes the start of a base64 text, with no need to decode the whole text. */
  private object Base64Prefix:
    private val Alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    private val Values: Array[Int] =
      val table = Array.fill(128)(-1)
      Alphabet.indices.foreach(i => table(Alphabet.charAt(i)) = i)
      // The URL-safe alphabet too.
      table('-') = 62
      table('_') = 63
      table

    /** The first `maxBytes` bytes (or fewer) of the base64 text `data`. Characters out of the alphabet are skipped. */
    def decode(data: String, maxBytes: Int): Array[Byte] =
      val out    = Array.newBuilder[Byte]
      var count  = 0
      var buffer = 0
      var bits   = 0
      var i      = 0
      while i < data.length && count < maxBytes do
        val c = data.charAt(i)
        if c == '=' then i = data.length
        else
          val v = if c < 128 then Values(c) else -1
          if v >= 0 then
            buffer = (buffer << 6) | v
            bits += 6
            if bits >= 8 then
              bits -= 8
              out += ((buffer >> bits) & 0xff).toByte
              count += 1
          i += 1
      out.result()

  /** Decodes the `%XX` escapes of a URL text as UTF-8. */
  private object PercentDecoding:
    def decode(text: String): Option[String] =
      scala.util.Try(java.net.URLDecoder.decode(text.replace("+", "%2B"), "UTF-8")).toOption
