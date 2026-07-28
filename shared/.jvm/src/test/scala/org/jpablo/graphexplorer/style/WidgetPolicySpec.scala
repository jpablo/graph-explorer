package org.jpablo.graphexplorer.style

import munit.FunSuite

import java.io.File
import scala.io.Source

/** The daisyUI usage rule, executable.
  *
  * A daisyUI class is either POLICY or STRUCTURE. Policy classes encode an
  * app-wide decision — what a button, form control, modal, tooltip, or
  * notification looks like — and may appear ONLY inside `viewer/.../widgets/`,
  * where each has one owning widget; everywhere else calls the widget. This is
  * not a style preference: the 5.7.4 audit found every dead-class bug
  * (`textarea-bordered` ×3, `form-control` ×2, `card-compact`) in INLINE
  * copies, while wrapped components were fixed once, in one file.
  *
  * Structure classes — card, navbar, menu, kbd, badge, tabs, join, dropdown
  * scaffolding, layout utilities — stay free: they are one-off composition
  * that reads like the daisyUI docs, and wrapping them would be ceremony.
  *
  * The check is deliberately dumb: any whitespace-separated token in a quoted
  * string on a `cls` line whose head segment is a policy class. Dumb survives
  * refactors; clever rots.
  */
class WidgetPolicySpec extends FunSuite:

  /** Class families whose spelling lives in widgets/ alone. */
  private val policyClasses = Set(
    "btn",      // Button / IconButton / Actions / extension methods
    "input",    // Inputs.scala
    "select",   // Inputs.scala (Select)
    "textarea", // Inputs.scala (LabelTextArea)
    "checkbox", // Inputs.scala (LabeledCheckbox)
    "toggle",   // Inputs.scala (LabeledCheckbox)
    "radio",    // Inputs.scala (FilterChips)
    "range",    // Inputs.scala (sliders)
    "modal",    // Dialog.scala
    "tooltip",  // Tooltip.scala / IconButton's iconShell
    "alert",    // Alert.scala
    "toast",    // Alert.scala
    "filter",   // Inputs.scala (FilterChips)
    "swap"      // Inputs.scala (SwapIcon)
  )

  /** Tokens whose head segment collides with a policy class but are NOT
    * daisyUI component classes (Tailwind utilities, mostly). */
  private val falseFriends = Set(
    "select-none", // Tailwind user-select
    "select-text",
    "select-all"
  )

  private lazy val repoRoot: File =
    Iterator
      .iterate(File(sys.props("user.dir")).getCanonicalFile)(_.getParentFile)
      .takeWhile(_ != null)
      .find(d => File(d, "build.sbt").isFile)
      .getOrElse(sys.error("repo root (build.sbt) not found"))

  private def scalaFilesUnder(dir: File): Seq[File] =
    val entries = Option(dir.listFiles()).map(_.toSeq).getOrElse(Seq.empty)
    entries.filter(_.isFile).filter(_.getName.endsWith(".scala")) ++
      entries.filter(_.isDirectory).flatMap(scalaFilesUnder)

  private val quoted = "\"([^\"]*)\"".r

  test("daisyUI policy classes appear only inside widgets/"):
    val viewerSrc = File(repoRoot, "viewer/src/main/scala")
    assert(viewerSrc.isDirectory, s"viewer sources not found at $viewerSrc")
    val offenders =
      for
        file <- scalaFilesUnder(viewerSrc)
        if !file.getPath.replace('\\', '/').contains("/widgets/")
        (line, idx) <- {
          val s = Source.fromFile(file, "UTF-8")
          try s.getLines().zipWithIndex.toVector
          finally s.close()
        }
        // `cls` lines carry class strings; `tipPos` is the tooltip-position
        // parameter, the one non-cls channel a policy token can travel through.
        if (line.contains("cls") || line.contains("tipPos")) && !line.trim.startsWith("//")
        m     <- quoted.findAllMatchIn(line)
        token <- m.group(1).split("\\s+")
        head = token.takeWhile(_ != '-')
        if policyClasses(head) && !falseFriends(token)
      yield s"  ${file.getPath.stripPrefix(repoRoot.getPath + "/")}:${idx + 1}: '$token'"
    assert(
      offenders.isEmpty,
      s"""daisyUI POLICY classes outside widgets/ — call the owning widget instead
         |(or, if a token is a Tailwind utility, add it to falseFriends):
         |${offenders.mkString("\n")}""".stripMargin
    )
