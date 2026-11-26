package org.jpablo.graphexplorer.viewer.utils

import munit.FunSuite
import org.jpablo.graphexplorer.viewer.backends.mermaid.MermaidImageSources

/** The links and the image paths of the files next to a diagram.
  *
  * A drop of a file on a diagram with a file gives a node with a link to the file. The link is a path relative to the
  * folder of the diagram when the file is in that folder or below it. Thus the folder can move as one unit. Else the
  * link is an absolute `file://` URL. A diagram with no file has no folder, so its links are always URLs.
  */
class FileLinksSpec extends FunSuite:

  test("a file in the folder of the diagram, or below it, gets a relative link"):
    assertEquals(FileLinks.linkFor(Some("/Users/me/work/arch.dot"), "/Users/me/work/report.pdf"), "report.pdf")
    assertEquals(
      FileLinks.linkFor(Some("/Users/me/work/arch.dot"), "/Users/me/work/docs/my report.pdf"),
      "docs/my report.pdf"
    )

  test("a file outside the folder of the diagram gets a file URL"):
    assertEquals(
      FileLinks.linkFor(Some("/Users/me/work/arch.dot"), "/Users/me/other/report 2026.pdf"),
      "file:///Users/me/other/report%202026.pdf"
    )
    // A folder with the same start is a different folder.
    assertEquals(
      FileLinks.linkFor(Some("/Users/me/work/arch.dot"), "/Users/me/workshop/a.pdf"),
      "file:///Users/me/workshop/a.pdf"
    )

  test("a diagram with no file gives a file URL"):
    assertEquals(FileLinks.linkFor(None, "/tmp/a#b?.txt"), "file:///tmp/a%23b%3F.txt")
    assertEquals(FileLinks.fileUrl("/tmp/é ü.txt"), "file:///tmp/%C3%A9%20%C3%BC.txt")

  test("a local file link is a file URL or a relative path"):
    for link <- List("report.pdf", "docs/my report.pdf", "arch.assets/img-3f2a.png", "file:///tmp/a.txt", "FILE:///tmp/a") do
      assert(FileLinks.isLocalFileLink(link), link)
    for link <- List(
        "",
        "https://example.com",
        "javascript:alert(1)",
        "mailto:a@example.com",
        "data:image/png;base64,AA",
        "/etc/passwd",
        "#top",
        "-a",
        "~/x",
        "a\nb"
      )
    do assert(!FileLinks.isLocalFileLink(link), link)

  test("an image path relative to the diagram"):
    assert(FileLinks.isRelativePath("arch.assets/img-3f2a.png"))
    assert(!FileLinks.isRelativePath("data:image/png;base64,AA"))
    assert(!FileLinks.isRelativePath("https://example.com/a.png"))
    assert(!FileLinks.isRelativePath("asset://localhost/%2Fa.png"))
    assert(!FileLinks.isRelativePath("file:///a.png"))

  test("the folder of a diagram and a path in it"):
    assertEquals(FileLinks.dirOf("/Users/me/work/arch.dot"), "/Users/me/work")
    assertEquals(FileLinks.resolve("/Users/me/work/arch.dot", "arch.assets/a.png"), "/Users/me/work/arch.assets/a.png")

  // ------------------------------------------------------------------ the images of a Mermaid text

  test("the images of a Mermaid text change, and the rest of the text stays"):
    val text =
      """flowchart LR
        |  pic@{ img: "arch.assets/a.png", w: 40, h: 20, constraint: "on" }
        |  web@{ img: "https://example.com/b.png" }
        |  box["img: \"arch.assets/a.png\""]
        |  pic --> web""".stripMargin
    val out = MermaidImageSources.rewrite(text, src => Option.when(src == "arch.assets/a.png")("asset://localhost/%2Fw%2Fa.png"))
    assertEquals(
      out,
      text.replace("""img: "arch.assets/a.png", w""", """img: "asset://localhost/%2Fw%2Fa.png", w""")
    )

  test("the image paths of a Mermaid text"):
    val text = "flowchart LR\n  a@{ img: \"x/a.png\" }\n  b@{ img: 'y.png', label: \"B\" }\n  c --> d"
    assertEquals(MermaidImageSources.sources(text), List("x/a.png", "y.png"))
