package org.jpablo.typeexplorer.ui.app.components

import com.raquo.laminar.api.L.*
import org.jpablo.typeexplorer.ui.app.client.{fetchClasses, fetchDocuments, fetchInheritanceSVGDiagram}
import org.jpablo.typeexplorer.ui.app.components.appFooter
import org.jpablo.typeexplorer.ui.app.components.tabs.tabsArea
import io.laminext.syntax.core.*
import org.jpablo.typeexplorer.shared.models
import org.jpablo.typeexplorer.shared.inheritance.Related
import org.jpablo.typeexplorer.ui.app.Path
import org.jpablo.typeexplorer.ui.app.components.state.{SelectedSymbol, selectedSymbolToDiagram}
import com.raquo.airstream.core.EventStream
import org.scalajs.dom

object TopLevel {

  val $newDiagramType = EventBus[DiagramType]
  val $selectedUri    = EventBus[Path]
  val selectedSymbol  = SelectedSymbol()
  val projectPath     = storedString("projectPath", initial = "")
  val $projectPath    = projectPath.signal.map(Path.apply)
  val $documents      = fetchDocuments($projectPath)
  val $classes        = fetchClasses($projectPath)
  val $inheritance    = selectedSymbolToDiagram(selectedSymbol, $projectPath)

  def topLevel: Div =
    div(
      idAttr := "te-toplevel",
      appHeader($newDiagramType, projectPath),
      tabsArea(
        $projectPath    = $projectPath,
        $documents      = $documents,
        $svgDiagram     = $inheritance,
        $classes        = $classes,
        selectedSymbol  = selectedSymbol,
        $selectedUri    = $selectedUri
      ),
      appFooter
    )

}
