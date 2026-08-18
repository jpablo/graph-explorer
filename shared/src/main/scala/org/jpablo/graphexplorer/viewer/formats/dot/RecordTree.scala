package org.jpablo.graphexplorer.viewer.formats.dot

import org.jpablo.graphexplorer.graphviz.layout.RecordLabel
import org.jpablo.graphexplorer.viewer.formats.dot.attributes.Rankdir

/** Immutable editing model for `shape=record` labels.
  *
  * The grammar (`f | f | f`, `{…}` flips orientation, `<id>` ports,
  * `\{ \} \| \< \> \ ` escapes) is owned by the engine's
  * [[RecordLabel]] parser — this wrapper converts its mutable layout tree
  * into a value type suitable for structural edits, and serializes back to
  * a canonical label string.
  *
  * Invariants of a canonical tree (what [[parse]] produces and edits keep):
  *   - leaf `text` is in record-escaped form, with unescaped spaces trimmed
  *     and collapsed (exactly what the parser would do anyway);
  *   - leaf `text` never ends in an unpaired backslash (it would swallow
  *     the following separator when serialized);
  *   - `port` is `None` rather than `Some("")`, and empty text is `""`
  *     rather than the parser's internal `" "`;
  *   - groups are never empty.
  *
  * Orientation is NOT stored: it is depth parity ([[parentIsLR]]), so
  * structural edits must preserve nesting depth of surviving cells —
  * this is why [[removeCell]] only collapses a singleton group when its
  * child is a leaf.
  *
  * HTML-in-record labels (`AttrEq(html=true)`) use a different escaping
  * mode and are out of scope: callers should not offer structural editing
  * for them.
  */
sealed trait RecordTree derives CanEqual

object RecordTree:
  // A sealed trait (not an enum) so the constructors keep their precise
  // types: the API distinguishes Group (the only thing a Path can traverse
  // and the type of the root) from Leaf.
  final case class Leaf(port: Option[String], text: String)               extends RecordTree
  final case class Group(port: Option[String], children: Vector[RecordTree]) extends RecordTree

  /** Child indices from the root group down to a cell. `Nil` = the root. */
  type Path = List[Int]

  private val Specials = Set('{', '}', '|', '<', '>')

  // ── label ⇄ tree ───────────────────────────────────────────────────────

  def parse(label: String): Group =
    Group(None, RecordLabel.parse(label, topLR = true).flds.map(fromField))

  private def fromField(f: RecordLabel.Field): RecordTree =
    val port = f.id.map(_.trim).filter(_.nonEmpty)
    if f.isLeaf then
      val text = f.text.map(t => if t == " " then "" else t).getOrElse("")
      // Collapse BEFORE dropping a dangling backslash: dropping one can itself
      // expose a trailing space, and that path re-trims on its own.
      Leaf(port, dropUnpairedTrailingBackslash(collapseUnescapedSpaces(text)))
    else Group(port, f.flds.map(fromField))

  /** Canonical label string: fields joined with `" | "`, `<port> ` prefixes,
    * `{…}` around groups. The root is never brace-wrapped — a fully braced
    * label is a root with a single Group child.
    */
  def serialize(root: Group): String =
    root.children.map(serializeField).mkString(" | ")

  private def serializeField(t: RecordTree): String = t match
    case Leaf(port, text) =>
      (port, text) match
        case (None, txt)     => txt
        case (Some(p), "")   => s"<$p>"
        case (Some(p), txt)  => s"<$p> $txt"
    case Group(port, children) =>
      val inner = children.map(serializeField).mkString(" | ")
      port.fold(s"{$inner}")(p => s"<$p>{$inner}")

  // ── queries ────────────────────────────────────────────────────────────

  def at(root: Group, path: Path): Option[RecordTree] =
    path.foldLeft(Option[RecordTree](root)): (acc, i) =>
      acc.flatMap:
        case Group(_, cs) => cs.lift(i)
        case _: Leaf      => None

  /** Paths of all leaf cells, in field order. */
  def leafPaths(root: Group): Vector[Path] =
    def go(t: RecordTree, acc: List[Int]): Vector[Path] = t match
      case _: Leaf      => Vector(acc.reverse)
      case Group(_, cs) => cs.zipWithIndex.flatMap((c, i) => go(c, i :: acc))
    root.children.zipWithIndex.flatMap((c, i) => go(c, List(i)))

  /** All leaf cells, in field order. */
  def leaves(root: Group): Vector[Leaf] =
    def go(t: RecordTree): Vector[Leaf] = t match
      case l: Leaf      => Vector(l)
      case Group(_, cs) => cs.flatMap(go)
    root.children.flatMap(go)

  /** The leaf closest to `path` (after an edit invalidated it): indices are
    * clamped level by level, then the walk descends to the first leaf.
    */
  def nearestLeafPath(root: Group, path: Path): Path =
    def firstLeaf(t: RecordTree, acc: List[Int]): Path = t match
      case _: Leaf      => acc.reverse
      case Group(_, cs) => cs.headOption.fold(acc.reverse)(c => firstLeaf(c, 0 :: acc))
    def go(t: RecordTree, p: Path, acc: List[Int]): Path = (t, p) match
      case (_: Leaf, _)   => acc.reverse
      case (g: Group, Nil) => firstLeaf(g, acc)
      case (Group(_, cs), i :: rest) =>
        cs.headOption match
          case None => acc.reverse
          case Some(_) =>
            val j = i.max(0).min(cs.length - 1)
            go(cs(j), if j == i then rest else Nil, j :: acc)
    go(root, path, Nil)

  /** Flow of the group CONTAINING the cell at `path`: true = its fields run
    * left→right. The root flows [[topLRFor]] and each `{}` flips.
    */
  def parentIsLR(path: Path, topLR: Boolean): Boolean =
    if (path.length - 1) % 2 == 0 then topLR else !topLR

  /** Top-level flow of a record label (`!realflip`): horizontal for
    * `rankdir=TB/BT`, vertical for `LR/RL`.
    */
  def topLRFor(rankdir: Rankdir): Boolean = rankdir match
    case Rankdir.TB | Rankdir.BT => true
    case Rankdir.LR | Rankdir.RL => false

  // ── edits (all total: an invalid/stale path returns the input) ─────────

  /** Set a leaf's text from dialog (unescaped) text. */
  def setText(root: Group, path: Path, display: String): Group =
    modifyCellAt(root, path):
      case l: Leaf  => l.copy(text = storedText(display))
      case g: Group => g

  /** Set (or clear) the port of the cell at `path`. */
  def setPort(root: Group, path: Path, port: Option[String]): Group =
    val clean = port.map(_.trim).filter(_.nonEmpty)
    modifyCellAt(root, path):
      case l: Leaf  => l.copy(port = clean)
      case g: Group => g.copy(port = clean)

  /** Every port name in the tree (leaves and groups). */
  def ports(root: Group): Set[String] =
    def go(t: RecordTree): Set[String] = t match
      case Leaf(p, _)   => p.toSet
      case Group(p, cs) => p.toSet ++ cs.flatMap(go)
    go(root)

  /** Insert an empty cell next to the cell at `path`, in the same group.
    * @return the new tree and the new cell's path
    */
  def insertSibling(root: Group, path: Path, after: Boolean): (Group, Path) =
    if path.isEmpty || at(root, path).isEmpty then (root, path)
    else
      val parent = path.init
      val idx    = if after then path.last + 1 else path.last
      val newRoot = modifyGroupAt(root, parent): g =>
        g.copy(children = g.children.patch(idx, Vector(Leaf(None, "")), 0))
      (newRoot, parent :+ idx)

  /** Split a leaf perpendicular to its group's flow: the leaf becomes a
    * `{leaf | empty}` group (keeping its port on the inner leaf).
    * @return the new tree and the new empty cell's path
    */
  def splitCell(root: Group, path: Path): (Group, Path) =
    at(root, path) match
      case Some(l: Leaf) =>
        val newRoot = modifyCellAt(root, path)(_ => Group(None, Vector(l, Leaf(None, ""))))
        (newRoot, path :+ 1)
      case _ => (root, path)

  /** Remove the cell at `path`. Groups emptied by the removal disappear;
    * a group left with a single LEAF collapses to that leaf (a single Group
    * child is kept — unnesting it would flip its orientation). An emptied
    * record keeps one empty cell.
    * @return the new tree and the nearest surviving leaf's path
    */
  def removeCell(root: Group, path: Path): (Group, Path) =
    if path.isEmpty || at(root, path).isEmpty then (root, path)
    else
      val newRoot = removeIn(root, path).getOrElse(Group(None, Vector(Leaf(None, ""))))
      (newRoot, nearestLeafPath(newRoot, path))

  private def removeIn(g: Group, path: Path): Option[Group] =
    val newChildren = path match
      case Nil      => g.children
      case i :: Nil => g.children.patch(i, Vector.empty, 1)
      case i :: rest =>
        g.children.lift(i) match
          case Some(child: Group) =>
            removeIn(child, rest) match
              case Some(updated) => g.children.updated(i, normalizeGroup(updated))
              case None          => g.children.patch(i, Vector.empty, 1)
          case _ => g.children
    if newChildren.isEmpty then None else Some(g.copy(children = newChildren))

  private def normalizeGroup(g: Group): RecordTree = g.children match
    case Vector(l: Leaf) => if l.port.isDefined then l else l.copy(port = g.port)
    case _               => g

  private def modifyGroupAt(root: Group, path: Path)(f: Group => Group): Group =
    path match
      case Nil => f(root)
      case i :: rest =>
        root.children.lift(i) match
          case Some(g: Group) =>
            root.copy(children = root.children.updated(i, modifyGroupAt(g, rest)(f)))
          case _ => root

  private def modifyCellAt(root: Group, path: Path)(f: RecordTree => RecordTree): Group =
    path match
      case Nil => root
      case _ =>
        modifyGroupAt(root, path.init): g =>
          val i = path.last
          if g.children.indices.contains(i) then g.copy(children = g.children.updated(i, f(g.children(i))))
          else g

  // ── cell text ⇄ dialog text ────────────────────────────────────────────

  /** Stored (record-escaped) → dialog text: `\{ \} \| \< \>` unescape and
    * `\n` becomes a real newline; every other backslash sequence (`\l`,
    * `\N`, `\\`, …) is label-machinery syntax and passes through verbatim.
    */
  def displayText(stored: String): String = unescapeWith(stored, newlineAsChar = true)

  /** Stored → plain-label text (for splitting a record into simple nodes):
    * only the record specials unescape — `\n`/`\l`/… stay, plain DOT labels
    * use the same escapes.
    */
  def unescapeSpecials(stored: String): String = unescapeWith(stored, newlineAsChar = false)

  private def unescapeWith(stored: String, newlineAsChar: Boolean): String =
    val sb = StringBuilder()
    var i  = 0
    while i < stored.length do
      val c = stored.charAt(i)
      if c == '\\' && i + 1 < stored.length then
        val n = stored.charAt(i + 1)
        if Specials(n) then sb += n
        else if n == 'n' && newlineAsChar then sb += '\n'
        else { sb += c; sb += n }
        i += 2
      else
        sb += c
        i += 1
    sb.result()

  /** Dialog text → canonical stored form: newlines become `\n`, control
    * characters drop, record specials escape, unescaped spaces collapse and
    * trim (all exactly what the parser would do). User-typed escapes such
    * as `\l` pass through; a trailing unpaired backslash is dropped (it
    * would swallow the following separator).
    */
  def storedText(display: String): String =
    val normalized = display.replace("\r\n", "\n").replace('\r', '\n')
    val sb         = StringBuilder()
    for c <- normalized do
      if c == '\n' then sb ++= "\\n"
      else if c < ' ' then ()
      else if Specials(c) then { sb += '\\'; sb += c }
      else sb += c
    dropUnpairedTrailingBackslash(sb.result().trim.replaceAll(" {2,}", " "))

  /** Collapse and trim runs of UNESCAPED spaces.
    *
    * Restores the invariant this file documents at the top: leaf `text` holds
    * unescaped spaces already trimmed and collapsed. The engine's parser
    * unescapes `\ ` into a plain space, which can land beside a space that was
    * already there — and nothing put the run back into canonical form, so
    * `serialize ∘ parse` took a SECOND pass to settle. `"a \ b"` became
    * `"a  b"`, and only the next parse made it `"a b"`.
    *
    * The same bug as [[trimTrailingUnescapedSpaces]] approached from the other
    * side: an unescape reveals whitespace, and whatever reveals it owes the
    * caller a re-normalization. A ScalaCheck seed found that one; a ScalaCheck
    * seed found this one, and both counterexamples are pinned in RecordTreeSpec.
    *
    * Escape-aware, so `\ ` written deliberately by a user survives — only runs
    * that are actually unescaped collapse.
    */
  private def collapseUnescapedSpaces(s: String): String =
    val sb                    = StringBuilder()
    var i                     = 0
    var lastWasUnescapedSpace = false
    while i < s.length do
      val c = s.charAt(i)
      if c == '\\' && i + 1 < s.length then
        sb += c
        sb += s.charAt(i + 1)
        i += 2
        lastWasUnescapedSpace = false
      else
        if c == ' ' then
          if !lastWasUnescapedSpace then sb += c
          lastWasUnescapedSpace = true
        else
          sb += c
          lastWasUnescapedSpace = false
        i += 1
    val collapsed = sb.result()
    // A leading space cannot be escaped (nothing precedes it), so index 0 is
    // the only case to consider on that side.
    val fromStart = if collapsed.startsWith(" ") then collapsed.drop(1) else collapsed
    trimTrailingUnescapedSpaces(fromStart)

  private def dropUnpairedTrailingBackslash(s: String): String =
    var i = 0
    while i < s.length do
      if s.charAt(i) == '\\' then
        if i + 1 < s.length then i += 2
        else return trimTrailingUnescapedSpaces(s.dropRight(1)) // lone backslash at the end
      else i += 1
    s

  /** Drop trailing spaces that are not themselves escaped.
    *
    * Only reachable once a dangling backslash has been removed, and that is the
    * point: removing it can expose a space that was unescaped all along, and
    * leaf `text` is documented to hold unescaped spaces already trimmed. Without
    * this, `"x \"` serializes to `"x "` and only a SECOND parse trims it to
    * `"x"` — so `serialize ∘ parse` needed two passes to settle instead of one.
    * A ScalaCheck seed found it; the literal case is pinned in RecordTreeSpec.
    *
    * Escape-aware so the trim stops at the text rather than eating into it. Via
    * `parse` that guard is belt-and-braces — the engine has already normalized a
    * trailing `\ ` by the time this runs — but the other caller escapes dialog
    * text itself, and there a trailing escaped space is the user's to keep.
    */
  private def trimTrailingUnescapedSpaces(s: String): String =
    var end = s.length
    while end > 0 && s.charAt(end - 1) == ' ' && !isEscaped(s, end - 1) do end -= 1
    s.substring(0, end)

  private def isEscaped(s: String, index: Int): Boolean =
    var backslashes = 0
    var i           = index - 1
    while i >= 0 && s.charAt(i) == '\\' do
      backslashes += 1
      i -= 1
    backslashes % 2 == 1

end RecordTree
