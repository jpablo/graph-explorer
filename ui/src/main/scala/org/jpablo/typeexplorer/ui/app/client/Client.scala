package org.jpablo.typeexplorer.ui.app.client

import com.raquo.laminar.api.L.*
import concurrent.ExecutionContext.Implicits.global
import io.laminext.fetch.*
import io.laminext.syntax.core.StoredString
import org.jpablo.typeexplorer.protos.{TextDocumentsWithSource, TextDocumentsWithSourceSeq}
import org.jpablo.typeexplorer.shared.inheritance.PlantumlInheritance.Options
import org.jpablo.typeexplorer.shared.inheritance.{InheritanceDiagram, Related}
import org.jpablo.typeexplorer.shared.models.{Namespace, Symbol}
import org.jpablo.typeexplorer.shared.webApp.InheritanceReq
import org.jpablo.typeexplorer.ui.app.Path
import org.jpablo.typeexplorer.ui.app.components.DiagramType
import org.jpablo.typeexplorer.ui.app.components.state.AppState
import org.jpablo.typeexplorer.ui.app.components.tabs.inheritanceTab.InheritanceSvgDiagram
import org.jpablo.typeexplorer.ui.app.console
import org.scalajs.dom
import scala.scalajs.js.typedarray.Int8Array
import scalajs.js.URIUtils.encodeURIComponent
import zio.json.*

val basePath = "http://localhost:8090/"

def fetchBase(path: String): FetchEventStreamBuilder =
  Fetch.get(basePath + path)


def fetchDocuments($projectPath: Signal[Path]): EventStream[List[TextDocumentsWithSource]] =
  for
    path <- $projectPath
    lst <-
      if path.toString.isEmpty then
        EventStream.fromValue(List.empty)
      else
        for response <- fetchBase("semanticdb?path=" + path).arrayBuffer yield
          val ia = Int8Array(response.data, 0, length = response.data.byteLength)
          TextDocumentsWithSourceSeq.parseFrom(ia.toArray).documentsWithSource.toList.sortBy(_.semanticDbUri)
  yield
    lst


def fetchInheritanceDiagram(projectPath: Path): Signal[InheritanceDiagram] = {
  for
    response <- fetchBase("classes?path=" + projectPath).text
    classes  <- EventStream.fromTry {
      response.data
        .fromJson[InheritanceDiagram].left
        .map(Exception(_))
        .toTry
    }
  yield
    classes
}.startWith(InheritanceDiagram.empty)

def fetchInheritanceSVGDiagram(appState: AppState): EventStream[InheritanceSvgDiagram] =
  val combined =
    appState.$projectPath
      .combineWith(
        appState.inheritanceTabState.$activeSymbols.signal,
        appState.inheritanceTabState.$options.signal
      )
  for
    (projectPath, symbols, options) <- combined
    parser = dom.DOMParser()
    diagram <-
      if projectPath.toString.isEmpty then
        EventStream.fromValue(svg.svg().ref)
      else
        val body = InheritanceReq(List(projectPath.toString), symbols, InheritanceReq.Config(options.fields, options.signatures))
        val req = Fetch.post(basePath + "inheritance", body.toJson)
        req.text.map { fetchResponse =>
          parser
            .parseFromString(fetchResponse.data, dom.MIMEType.`image/svg+xml`)
            .documentElement
            .asInstanceOf[dom.SVGElement]
        }
  yield
    InheritanceSvgDiagram(diagram)


def fetchCallGraphSVGDiagram($diagram: Signal[(DiagramType, Path)]): EventStream[dom.Element] =
  val parser = dom.DOMParser()
  for
    (diagramType, path) <- $diagram
    doc <- if path.toString.isEmpty
    then
      EventStream.fromValue(div().ref)
    else
      val fetchEventStreamBuilder = diagramType match
        case DiagramType.Inheritance => fetchBase("inheritance?path=" + path)
        case DiagramType.CallGraph   => fetchBase("call-graph?path=" + path)

      fetchEventStreamBuilder.text.map { fetchResponse =>
        parser.parseFromString(fetchResponse.data, dom.MIMEType.`image/svg+xml`).documentElement
      }
//      errorNode = doc.querySelector("parsererror")
  yield doc

def fetchSourceCode =
  AppState.$projectPath.map { $projectPath => (selectedPath: Path) =>
    for
      projectPath <- $projectPath
      response    <- fetchBase(s"source?path=${encodeURIComponent(projectPath.toString + "/" + selectedPath)}").text
    yield
      response.data
  }
