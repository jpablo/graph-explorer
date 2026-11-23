package org.jpablo.graphexplorer.graphviz.html

/** HTML-4.0 named character entities (GENERATED from gv's
  * `lib/common/entities.html`, the source of `entities.h` — see
  * `entities.py`). Maps entity name → Unicode code point. Used by
  * [[HtmlParser]] (gv's `scanEntity`; unknown names stay literal), and by
  * [[htmlEntityUTF8]]/[[latin1ToUTF8]] for each plain label, the json
  * strings of a Latin-1 graph, and the tooltips. 252 entries.
  */
private[graphexplorer] object Entities:
  val codepoints: Map[String, Int] = Map(
    "AElig" -> 198, "Aacute" -> 193, "Acirc" -> 194, "Agrave" -> 192, "Alpha" -> 913, "Aring" -> 197,
    "Atilde" -> 195, "Auml" -> 196, "Beta" -> 914, "Ccedil" -> 199, "Chi" -> 935, "Dagger" -> 8225,
    "Delta" -> 916, "ETH" -> 208, "Eacute" -> 201, "Ecirc" -> 202, "Egrave" -> 200, "Epsilon" -> 917,
    "Eta" -> 919, "Euml" -> 203, "Gamma" -> 915, "Iacute" -> 205, "Icirc" -> 206, "Igrave" -> 204,
    "Iota" -> 921, "Iuml" -> 207, "Kappa" -> 922, "Lambda" -> 923, "Mu" -> 924, "Ntilde" -> 209,
    "Nu" -> 925, "OElig" -> 338, "Oacute" -> 211, "Ocirc" -> 212, "Ograve" -> 210, "Omega" -> 937,
    "Omicron" -> 927, "Oslash" -> 216, "Otilde" -> 213, "Ouml" -> 214, "Phi" -> 934, "Pi" -> 928,
    "Prime" -> 8243, "Psi" -> 936, "Rho" -> 929, "Scaron" -> 352, "Sigma" -> 931, "THORN" -> 222,
    "Tau" -> 932, "Theta" -> 920, "Uacute" -> 218, "Ucirc" -> 219, "Ugrave" -> 217, "Upsilon" -> 933,
    "Uuml" -> 220, "Xi" -> 926, "Yacute" -> 221, "Yuml" -> 376, "Zeta" -> 918, "aacute" -> 225,
    "acirc" -> 226, "acute" -> 180, "aelig" -> 230, "agrave" -> 224, "alefsym" -> 8501, "alpha" -> 945,
    "amp" -> 38, "and" -> 8743, "ang" -> 8736, "aring" -> 229, "asymp" -> 8776, "atilde" -> 227,
    "auml" -> 228, "bdquo" -> 8222, "beta" -> 946, "brvbar" -> 166, "bull" -> 8226, "cap" -> 8745,
    "ccedil" -> 231, "cedil" -> 184, "cent" -> 162, "chi" -> 967, "circ" -> 710, "clubs" -> 9827,
    "cong" -> 8773, "copy" -> 169, "crarr" -> 8629, "cup" -> 8746, "curren" -> 164, "dArr" -> 8659,
    "dagger" -> 8224, "darr" -> 8595, "deg" -> 176, "delta" -> 948, "diams" -> 9830, "divide" -> 247,
    "eacute" -> 233, "ecirc" -> 234, "egrave" -> 232, "empty" -> 8709, "emsp" -> 8195, "ensp" -> 8194,
    "epsilon" -> 949, "equiv" -> 8801, "eta" -> 951, "eth" -> 240, "euml" -> 235, "euro" -> 8364,
    "exist" -> 8707, "fnof" -> 402, "forall" -> 8704, "frac12" -> 189, "frac14" -> 188, "frac34" -> 190,
    "frasl" -> 8260, "gamma" -> 947, "ge" -> 8805, "gt" -> 62, "hArr" -> 8660, "harr" -> 8596,
    "hearts" -> 9829, "hellip" -> 8230, "iacute" -> 237, "icirc" -> 238, "iexcl" -> 161, "igrave" -> 236,
    "image" -> 8465, "infin" -> 8734, "int" -> 8747, "iota" -> 953, "iquest" -> 191, "isin" -> 8712,
    "iuml" -> 239, "kappa" -> 954, "lArr" -> 8656, "lambda" -> 955, "lang" -> 9001, "laquo" -> 171,
    "larr" -> 8592, "lceil" -> 8968, "ldquo" -> 8220, "le" -> 8804, "lfloor" -> 8970, "lowast" -> 8727,
    "loz" -> 9674, "lrm" -> 8206, "lsaquo" -> 8249, "lsquo" -> 8216, "lt" -> 60, "macr" -> 175,
    "mdash" -> 8212, "micro" -> 181, "middot" -> 183, "minus" -> 8722, "mu" -> 956, "nabla" -> 8711,
    "nbsp" -> 160, "ndash" -> 8211, "ne" -> 8800, "ni" -> 8715, "not" -> 172, "notin" -> 8713,
    "nsub" -> 8836, "ntilde" -> 241, "nu" -> 957, "oacute" -> 243, "ocirc" -> 244, "oelig" -> 339,
    "ograve" -> 242, "oline" -> 8254, "omega" -> 969, "omicron" -> 959, "oplus" -> 8853, "or" -> 8744,
    "ordf" -> 170, "ordm" -> 186, "oslash" -> 248, "otilde" -> 245, "otimes" -> 8855, "ouml" -> 246,
    "para" -> 182, "part" -> 8706, "permil" -> 8240, "perp" -> 8869, "phi" -> 966, "pi" -> 960,
    "piv" -> 982, "plusmn" -> 177, "pound" -> 163, "prime" -> 8242, "prod" -> 8719, "prop" -> 8733,
    "psi" -> 968, "quot" -> 34, "rArr" -> 8658, "radic" -> 8730, "rang" -> 9002, "raquo" -> 187,
    "rarr" -> 8594, "rceil" -> 8969, "rdquo" -> 8221, "real" -> 8476, "reg" -> 174, "rfloor" -> 8971,
    "rho" -> 961, "rlm" -> 8207, "rsaquo" -> 8250, "rsquo" -> 8217, "sbquo" -> 8218, "scaron" -> 353,
    "sdot" -> 8901, "sect" -> 167, "shy" -> 173, "sigma" -> 963, "sigmaf" -> 962, "sim" -> 8764,
    "spades" -> 9824, "sub" -> 8834, "sube" -> 8838, "sum" -> 8721, "sup" -> 8835, "sup1" -> 185,
    "sup2" -> 178, "sup3" -> 179, "supe" -> 8839, "szlig" -> 223, "tau" -> 964, "there4" -> 8756,
    "theta" -> 952, "thetasym" -> 977, "thinsp" -> 8201, "thorn" -> 254, "tilde" -> 732, "times" -> 215,
    "trade" -> 8482, "uArr" -> 8657, "uacute" -> 250, "uarr" -> 8593, "ucirc" -> 251, "ugrave" -> 249,
    "uml" -> 168, "upsih" -> 978, "upsilon" -> 965, "uuml" -> 252, "weierp" -> 8472, "xi" -> 958,
    "yacute" -> 253, "yen" -> 165, "yuml" -> 255, "zeta" -> 950, "zwj" -> 8205, "zwnj" -> 8204
  )

  /** ENTITY_NAME_LENGTH_MAX (entities.h). htmlEntity reads at most 8
    * characters of a name, and the `;` must come in these 8 characters. Thus
    * `&thetasym;` (8 letters) stays as it is in gv. */
  private val NameLengthMax = 8

  /** htmlEntity (lib/common/utils.c) for the entity after an `&` at `s(i - 1)`.
    * @return the code point (0 when no entity is decoded) and the index after
    *         the entity. A numeric entity with a `;` moves the index also when
    *         its value is 0 (`&#;`, `&#x;`, `&#0;`). Then gv keeps the `&` and
    *         drops the rest. Each other failure keeps the index at `i`. */
  private def htmlEntity(s: String, i: Int): (Int, Int) =
    def at(k: Int): Char = if k < s.length then s(k) else '\u0000'
    if at(i) == '#' then
      // `&#x` + at most 5 hex digits, or `&#` + at most 6 decimal digits (i < 8)
      val hex = at(i + 1) == 'x' || at(i + 1) == 'X'
      var k = if hex then 2 else 1
      var n = 0
      var stop = false
      while !stop && k < 8 do
        val ch = at(i + k)
        val d =
          if ch >= '0' && ch <= '9' then ch - '0'
          else if hex && ch >= 'a' && ch <= 'f' then ch - 'a' + 10
          else if hex && ch >= 'A' && ch <= 'F' then ch - 'A' + 10
          else -1
        if d >= 0 then { n = n * (if hex then 16 else 10) + d; k += 1 }
        else stop = true
      if stop && at(i + k) == ';' then (n, i + k + 1) else (0, i)
    else
      var k = 0
      while k < NameLengthMax do
        val ch = at(i + k)
        if ch == '\u0000' then return (0, i)
        if ch == ';' then
          return codepoints.get(s.substring(i, i + k)) match
            case Some(v) => (v, i + k + 1)
            case None    => (0, i)
        k += 1
      (0, i)

  /** The loop of htmlEntityUTF8 and latin1ToUTF8 (lib/common/utils.c): each
    * entity becomes its character, and each `&` that does not start an
    * entity stays. */
  private def decode(s: String): String =
    if s.indexOf('&') < 0 then return s
    val sb = new StringBuilder
    var i  = 0
    while i < s.length do
      val c = s(i)
      i += 1
      if c != '&' then sb += c
      else
        val (v, next) = htmlEntity(s, i)
        i = next
        if v != 0 then sb.appendAll(Character.toChars(v)) else sb += '&'
    sb.toString

  /** htmlEntityUTF8 (lib/common/utils.c): the text of a plain label for the
    * charset UTF-8. make_label (labels.c) calls it after
    * strdup_and_subst_obj0 and before make_simple_label. `&name;`, `&#N;`
    * and `&#xH;` become their characters.
    *
    * Not ported: gv writes the code points 0x7F, 0x7FF and those above
    * 0xFFFF as bytes that are not correct UTF-8. The port writes the correct
    * character. A Java string cannot hold the bad UTF-8 that gv reads as
    * Latin-1. */
  private[graphexplorer] def htmlEntityUTF8(s: String): String = decode(s)

  /** latin1ToUTF8 (lib/common/utils.c): the text of a plain label for the
    * charset Latin-1. Each byte of the input is a Latin-1 character, thus a
    * UTF-8 `é` (2 bytes) becomes the 2 characters `Ã©`. The entities decode
    * as in [[htmlEntityUTF8]]. */
  private[graphexplorer] def latin1ToUTF8(s: String): String =
    val latin =
      if s.forall(_ < 0x80) then s
      else new String(s.getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.ISO_8859_1)
    decode(latin)

  /** findCharset (lib/common/input.c): true when the `charset` of the root
    * graph names Latin-1. */
  private[graphexplorer] def isLatin1(charset: Option[String]): Boolean =
    charset.exists(c => LatinNames.contains(c.toLowerCase))

  private val LatinNames = Set("latin-1", "latin1", "l1", "iso-8859-1", "iso_8859-1", "iso8859-1", "iso-ir-100")
end Entities
