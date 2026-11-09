package org.jpablo.graphexplorer.gxcore.store

import munit.FunSuite
import org.jpablo.graphexplorer.gxcore.model.*

/** The pure plan of the migration from the old records to the metadata store
  * (plan §4). Each test names the rule that it checks.
  */
class MetadataMigrationSpec extends FunSuite:

  private val folder = "/home/me/Documents/Graph Explorer"
  private val now    = 1800000000000L

  private def bound(path: String): Option[String] = Some(s"file://$path")

  private def record(
      id:       String,
      name:     String,
      text:     String = "digraph G { a -> b }\n",
      binding:  Option[String] = None,
      format:   String = "DOT",
      metadata: DiagramMetadata => DiagramMetadata = identity
  ): LegacyRecord =
    LegacyRecord(
      id = id,
      format = format,
      text = text,
      origin = binding,
      metadata = metadata(DiagramMetadata(name = name)),
      createdAt = 1000L,
      updatedAt = 2000L
    )

  private def plan(
      records:  Vector[LegacyRecord],
      files:    Map[String, String] = Map.empty,
      existing: Set[String] = Set.empty,
      inFolder: String = folder
  ): MetadataMigrationPlan =
    MetadataMigration.plan(
      old = OldRecords(records, Vector.empty),
      readFile = files.get,
      diagramsFolder = inFolder,
      existingNames = existing,
      now = now
    )

  private def entryAt(p: MetadataMigrationPlan, path: String): LibraryEntry =
    p.entries.find(_.path == path).getOrElse(fail(s"no entry for $path in ${p.entries.map(_.path)}"))

  // ------------------------------------------------ a file that exists

  test("a record bound to a file that exists: the metadata moves to that file"):
    val text = "digraph G { a -> b }\n"
    val r    = record("arch", "arch", text, bound("/work/arch.dot"), metadata = m => m.copy(hiddenElements = Set("node:a")))
    val p    = plan(Vector(r), files = Map("/work/arch.dot" -> text))
    assertEquals(p.files, Vector.empty)
    assertEquals(p.entries.map(_.path), Vector("/work/arch.dot"))
    assertEquals(entryAt(p, "/work/arch.dot").metadata.hiddenElements, Set("node:a"))
    assert(p.report.exists(_.contains("/work/arch.dot")), p.report)

  test("a text that differs only in its line endings is the same text"):
    // The old model stored the text of a bound record with the line endings
    // of its file. Thus an LF copy of a CRLF file is the same text.
    val r = record("arch", "arch", "digraph G {\n  a\n}\n", bound("/work/arch.dot"))
    val p = plan(Vector(r), files = Map("/work/arch.dot" -> "digraph G {\r\n  a\r\n}\r\n"))
    assertEquals(p.files, Vector.empty)
    assertEquals(p.entries.map(_.path), Vector("/work/arch.dot"))

  test("a record whose text differs from its file: the record's text goes to Recovered/, and both are in the library"):
    val r = record("arch", "Architecture", "digraph G { a -> b -> c }\n", bound("/work/arch.dot"))
    val p = plan(Vector(r), files = Map("/work/arch.dot" -> "digraph G { a -> b }\n"))
    val recovered = s"$folder/Recovered/Architecture.dot"
    assertEquals(p.files, Vector(PlannedFile(recovered, "digraph G { a -> b -> c }\n")))
    assertEquals(p.entries.map(_.path).toSet, Set("/work/arch.dot", recovered))
    assert(p.report.exists(_.contains(recovered)), s"the report should name the recovered file: ${p.report}")

  test("the recovered file keeps the metadata of the record too"):
    val r = record(
      "arch",
      "Architecture",
      "digraph G { a -> b -> c }\n",
      bound("/work/arch.dot"),
      metadata = m => m.copy(hiddenElements = Set("node:c"), notes = "n")
    )
    val p = plan(Vector(r), files = Map("/work/arch.dot" -> "digraph G { a -> b }\n"))
    val recovered = entryAt(p, s"$folder/Recovered/Architecture.dot")
    assertEquals(recovered.metadata.hiddenElements, Set("node:c"))
    assertEquals(recovered.metadata.notes, "n")

  test("two records bound to one file: one gets the file, the other goes to Recovered/"):
    // One file has one set of metadata (ADR 0001). The record whose text is
    // the text of the file gets it. The other record keeps its own metadata
    // on a copy, so that the migration loses nothing.
    val text  = "digraph G { a -> b }\n"
    val older = record("one", "One", "digraph G { x }\n", bound("/work/arch.dot"), metadata = m => m.copy(tags = List("old")))
    val match_ = record("two", "arch", text, bound("/work/arch.dot"), metadata = m => m.copy(tags = List("live")))
    val p     = plan(Vector(older, match_), files = Map("/work/arch.dot" -> text))
    assertEquals(entryAt(p, "/work/arch.dot").metadata.tags, List("live"))
    assertEquals(p.files, Vector(PlannedFile(s"$folder/Recovered/One.dot", "digraph G { x }\n")))
    assertEquals(entryAt(p, s"$folder/Recovered/One.dot").metadata.tags.take(1), List("old"))

  // ------------------------------------------------ no file

  test("a record that is not bound: a new file in the folder, named after the record"):
    val r = record("n", "Notes", "digraph G { n }\n")
    val p = plan(Vector(r))
    assertEquals(p.files, Vector(PlannedFile(s"$folder/Notes.dot", "digraph G { n }\n")))
    assertEquals(p.entries.map(_.path), Vector(s"$folder/Notes.dot"))

  test("a record bound to a file that is missing: a new file in the folder, and the report names the old path"):
    val r = record("n", "Notes", "digraph G { n }\n", bound("/gone/notes.dot"))
    val p = plan(Vector(r))
    assertEquals(p.files, Vector(PlannedFile(s"$folder/Notes.dot", "digraph G { n }\n")))
    assert(p.report.exists(_.contains("/gone/notes.dot")), p.report)

  test("a record bound to an origin that is not a file: a new file in the folder"):
    val r = record("w", "Web", binding = Some("https://example.com/a.dot"))
    val p = plan(Vector(r))
    assertEquals(p.files.map(_.path), Vector(s"$folder/Web.dot"))
    assert(p.report.exists(_.contains("https://example.com/a.dot is not a file")), p.report)

  test("the extension comes from the format"):
    val p = plan(
      Vector(
        record("d", "Dot"),
        record("m", "Merm", "flowchart LR\n  a --> b\n", format = "Mermaid"),
        record("u", "Unknown", "flowchart LR\n  a --> b\n", format = "SomethingNew")
      )
    )
    assertEquals(
      p.files.map(_.path),
      Vector(s"$folder/Dot.dot", s"$folder/Merm.mmd", s"$folder/Unknown.mmd")
    )

  test("the file name is safe for a file system"):
    val p = plan(
      Vector(
        record("1", "a/b:c*d?"),
        record("2", ""),
        record("3", "CON"),
        record("4", "..hidden. "),
        record("5", "tab\there"),
        record("6", "x" * 400)
      )
    )
    val names = p.files.map(_.path.stripPrefix(s"$folder/"))
    assertEquals(names.take(5), Vector("a_b_c_d_.dot", "Untitled.dot", "CON_.dot", "hidden.dot", "tab_here.dot"))
    assert(names(5).getBytes("UTF-8").length <= 255, s"a file name has 255 bytes at most: ${names(5).length}")

  test("a long name with characters outside ASCII is cut at a character, not inside one"):
    val p    = plan(Vector(record("1", "é" * 300)))
    val name = p.files.head.path.stripPrefix(s"$folder/")
    assert(name.getBytes("UTF-8").length <= 255)
    assert(name.stripSuffix(".dot").forall(_ == 'é'), name)

  test("the file name is unique: Name.dot, Name 2.dot, Name 3.dot"):
    val p = plan(
      Vector(record("1", "Name"), record("2", "Name"), record("3", "Name")),
      existing = Set("Name.dot"),
      files = Map(s"$folder/Name.dot" -> "digraph { other }")
    )
    assertEquals(p.files.map(_.path), Vector(s"$folder/Name 2.dot", s"$folder/Name 3.dot", s"$folder/Name 4.dot"))

  test("the names that exist are compared without case"):
    // macOS and Windows have file systems that ignore case. A plan that made
    // `Name.dot` beside `name.dot` would fail there, or it would be a second
    // name for the same file.
    val p = plan(Vector(record("1", "Name")), existing = Set("name.DOT"))
    assertEquals(p.files.map(_.path), Vector(s"$folder/Name 2.dot"))

  test("a Recovered name is unique in Recovered/"):
    val r = record("arch", "Arch", "digraph { new }", bound("/work/arch.dot"))
    val p = plan(
      Vector(r),
      files = Map("/work/arch.dot" -> "digraph { old }", s"$folder/Recovered/Arch.dot" -> "digraph { other }"),
      existing = Set("Recovered/Arch.dot")
    )
    assertEquals(p.files.map(_.path), Vector(s"$folder/Recovered/Arch 2.dot"))

  test("a file that exists with the same text is used, not made again"):
    // This makes a run after a crash safe: the files of the first run are
    // found again, and the second run makes no copies of them.
    val text = "digraph G { n }\n"
    val p    = plan(Vector(record("n", "Notes", text)), existing = Set("Notes.dot"), files = Map(s"$folder/Notes.dot" -> text))
    assertEquals(p.files, Vector.empty)
    assertEquals(p.entries.map(_.path), Vector(s"$folder/Notes.dot"))

  test("a file that exists is used one time only"):
    val text = "digraph G { n }\n"
    val p = plan(
      Vector(record("1", "Notes", text), record("2", "Notes", text)),
      existing = Set("Notes.dot"),
      files = Map(s"$folder/Notes.dot" -> text)
    )
    assertEquals(p.files.map(_.path), Vector(s"$folder/Notes 2.dot"))
    assertEquals(p.entries.map(_.path), Vector(s"$folder/Notes.dot", s"$folder/Notes 2.dot"))

  test("a file that a bound record has is not used for a different record"):
    val text  = "digraph G { n }\n"
    val path  = s"$folder/Notes.dot"
    val owner = record("1", "Notes", text, bound(path))
    val other = record("2", "Notes", text)
    val p     = plan(Vector(other, owner), existing = Set("Notes.dot"), files = Map(path -> text))
    assertEquals(p.entries.map(_.path), Vector(path, s"$folder/Notes 2.dot"))
    assertEquals(p.files.map(_.path), Vector(s"$folder/Notes 2.dot"))

  test("the plan never overwrites a file that exists"):
    val existing = Set("A.dot", "A 2.dot", "Recovered/B.dot", "C.mmd")
    val files = Map(
      s"$folder/A.dot"           -> "x",
      s"$folder/A 2.dot"         -> "y",
      s"$folder/Recovered/B.dot" -> "z",
      s"$folder/C.mmd"           -> "w",
      "/work/b.dot"              -> "digraph { b }"
    )
    val p = plan(
      Vector(
        record("a", "A"),
        record("b", "B", "digraph { b2 }", bound("/work/b.dot")),
        record("c", "C", "flowchart LR\n a-->b", format = "Mermaid")
      ),
      files = files,
      existing = existing
    )
    val planned = p.files.map(_.path).toSet
    assert(planned.intersect(files.keySet).isEmpty, s"the plan writes over a file: ${planned.intersect(files.keySet)}")
    assertEquals(planned, Set(s"$folder/A 3.dot", s"$folder/Recovered/B 2.dot", s"$folder/C 2.mmd"))

  test("a Windows folder gets the separator of Windows"):
    val win = """C:\Users\me\Documents\Graph Explorer"""
    val r   = record("arch", "Arch", "digraph { new }", bound("/work/arch.dot"))
    val p   = plan(Vector(r, record("n", "Notes")), files = Map("/work/arch.dot" -> "digraph { old }"), inFolder = win)
    assertEquals(p.files.map(_.path), Vector(win + """\Recovered\Arch.dot""", win + """\Notes.dot"""))

  // ------------------------------------------------ the metadata

  test("a folder that is not the root becomes a tag"):
    val r = record("n", "Notes", metadata = m => m.copy(folder = FolderPath(List("systems", "core")), tags = List("a")))
    assertEquals(plan(Vector(r)).entries.head.metadata.tags, List("a", "systems/core"))

  test("the root folder gives no tag"):
    assertEquals(plan(Vector(record("n", "Notes"))).entries.head.metadata.tags, Nil)

  test("a name that is the base name of the file gives no tag"):
    val text = "digraph G { a }\n"
    val p    = plan(Vector(record("arch", "arch", text, bound("/work/arch.dot"))), files = Map("/work/arch.dot" -> text))
    assertEquals(p.entries.head.metadata.tags, Nil)

  test("a name that is the title in the DOT source gives no tag"):
    val text = "digraph G {\n  label=\"Architecture\"\n  a\n}\n"
    val p    = plan(Vector(record("arch", "Architecture", text, bound("/work/arch.dot"))), files = Map("/work/arch.dot" -> text))
    assertEquals(p.entries.head.metadata.tags, Nil)

  test("a name that is the title in the Mermaid source gives no tag"):
    val text = "---\ntitle: Flow\n---\nflowchart LR\n  a --> b\n"
    val p = plan(
      Vector(record("f", "Flow", text, bound("/work/flow.mmd"), format = "Mermaid")),
      files = Map("/work/flow.mmd" -> text)
    )
    assertEquals(p.entries.head.metadata.tags, Nil)

  test("a name that is neither the title nor the base name becomes a tag"):
    val text = "digraph G { a }\n"
    val p    = plan(Vector(record("arch", "Architecture", text, bound("/work/arch.dot"))), files = Map("/work/arch.dot" -> text))
    assertEquals(p.entries.head.metadata.tags, List("Architecture"))
    assert(p.report.exists(_.contains("tag")), p.report)

  test("a name that the file name cannot keep becomes a tag"):
    val p = plan(Vector(record("1", "Name"), record("2", "a/b")), existing = Set("Name.dot"))
    assertEquals(p.entries.map(_.metadata.tags), Vector(List("Name"), List("a/b")))

  test("the default name gives no tag"):
    val text = "digraph G { a }\n"
    val p    = plan(Vector(record("u", "Untitled", text, bound("/work/x.dot"))), files = Map("/work/x.dot" -> text))
    assertEquals(p.entries.head.metadata.tags, Nil)

  test("the tags, notes, hidden elements, collapsed groups, format flag and creation time stay"):
    val r = record(
      "n",
      "Notes",
      metadata = m =>
        m.copy(
          tags = List("x", "y"),
          notes = "remember",
          hiddenElements = Set("node:a", "arrow:a->b/0"),
          collapsedGroups = Set("group:svc"),
          autoDetectFormat = Some(false)
        )
    )
    val e = plan(Vector(r)).entries.head
    assertEquals(e.metadata.tags, List("x", "y"))
    assertEquals(e.metadata.notes, "remember")
    assertEquals(e.metadata.hiddenElements, Set("node:a", "arrow:a->b/0"))
    assertEquals(e.metadata.collapsedGroups, Set("group:svc"))
    assertEquals(e.metadata.autoDetectFormat, Some(false))
    assertEquals(e.createdAt, 1000L)
    assertEquals(e.updatedAt, now)

  test("a tag is not added two times"):
    val r = record("n", "Notes", metadata = m => m.copy(folder = FolderPath(List("x")), tags = List("x")))
    assertEquals(plan(Vector(r)).entries.head.metadata.tags, List("x"))

  test("an entry has the name and the folder that the store gives it when it reads it"):
    // Thus a write of the plan and a read of the store give equal entries.
    val r = record("n", "Notes", metadata = m => m.copy(folder = FolderPath(List("x"))))
    val e = plan(Vector(r)).entries.head
    assertEquals(e.metadata.name, "Notes")
    assertEquals(e.metadata.folder, FolderPath.root)

  test("no records give an empty plan"):
    assertEquals(plan(Vector.empty), MetadataMigrationPlan(Vector.empty, Vector.empty, Vector.empty))

  test("the report ends with a line for each old record that does not parse"):
    val p = MetadataMigration.plan(
      old = OldRecords(Vector(record("n", "Notes")), Vector("the old record diagrams/bad.json cannot be read (x).")),
      readFile = _ => None,
      diagramsFolder = folder,
      existingNames = Set.empty,
      now = now
    )
    assertEquals(p.entries.size, 1)
    assertEquals(p.report.last, "the old record diagrams/bad.json cannot be read (x).")

  test("pathIn gives the paths that the plan asks for, with the separator of the folder"):
    // A host reads the files of the folder before the plan. It must read the
    // same strings that the plan gives to `readFile`.
    assertEquals(MetadataMigration.pathIn(folder, "Recovered/Notes.dot"), s"$folder/Recovered/Notes.dot")
    assertEquals(MetadataMigration.pathIn(folder + "/", "Notes.dot"), s"$folder/Notes.dot")
    val win = """C:\Users\me\Documents\Graph Explorer"""
    assertEquals(MetadataMigration.pathIn(win, "Recovered/Notes.dot"), win + """\Recovered\Notes.dot""")
