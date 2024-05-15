package org.jpablo.typeexplorer.ui.app.client

import com.raquo.airstream.core.EventStream
import com.raquo.laminar.api.L.*
import io.laminext.fetch.*
import org.jpablo.typeexplorer.protos.{TextDocumentsWithSource, TextDocumentsWithSourceSeq}
import org.jpablo.typeexplorer.shared.inheritance.{InheritanceGraph, Path}
import org.jpablo.typeexplorer.shared.webApp.{Endpoints, InheritanceRequest, port}
import org.jpablo.typeexplorer.ui.app.components.DiagramType
import org.jpablo.typeexplorer.ui.app.components.state.Page
import org.jpablo.typeexplorer.ui.app.components.tabs.inheritanceTab.InheritanceSvgDiagram
import org.scalajs.dom
import zio.json.*

import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.URIUtils.encodeURIComponent
import scala.scalajs.js.typedarray.Int8Array

val basePath = s"http://localhost:$port"

def apiPath(path: String): String = s"$basePath/api/$path"

def fetchApiPath(path: String): FetchEventStreamBuilder =
  Fetch.get(apiPath(path))

def fetchDocuments(
    paths: Signal[List[Path]]
): EventStream[List[TextDocumentsWithSource]] =
  paths.flatMapSwitch: path =>
    if path.isEmpty then EventStream.fromValue(List.empty)
    else
      val qs = path.map(p => "path=" + p).mkString("&")
      for response <- fetchApiPath("semanticdb?" + qs).arrayBuffer yield
        val ia =
          Int8Array(response.data, 0, length = response.data.byteLength)
        TextDocumentsWithSourceSeq
          .parseFrom(ia.toArray)
          .documentsWithSource
          .toList
          .sortBy(_.semanticDbUri)

def fetchFullInheritanceGraph(
    basePaths: List[Path]
): Signal[InheritanceGraph] = {
  if basePaths.isEmpty then EventStream.empty
  else
    val qs = basePaths.map("path=" + _).mkString("&")
    fetchApiPath(s"${Endpoints.classes}?$qs").text
      .flatMapSwitch: response =>
        EventStream.fromTry {
          response.data
            .fromJson[InheritanceGraph]
            .left
            .map(Exception(_))
            .toTry
        }
}.startWith(InheritanceGraph.empty)

def fetchInheritanceSVGDiagram(
    basePaths: List[Path],
    page:      Page
): EventStream[InheritanceSvgDiagram] =
  if basePaths.isEmpty then EventStream.fromValue(InheritanceSvgDiagram.empty)
  else
    Fetch
      .post(
        url  = apiPath(Endpoints.inheritance),
        body = InheritanceRequest(basePaths.map(_.toString), page.activeSymbols, page.diagramOptions).toJson
      )
      .text
      .map: fetchResponse =>
        dom
          .DOMParser()
          .parseFromString(fetchResponse.data, dom.MIMEType.`image/svg+xml`)
          .documentElement
          .asInstanceOf[dom.SVGSVGElement]
      .map(InheritanceSvgDiagram(_))

def fetchCallGraphSVGDiagram(
    diagram: Signal[(DiagramType, Path)]
): EventStream[dom.Element] =
  val parser = dom.DOMParser()
  diagram.flatMapSwitch { case (diagramType, path) =>
    (if path.toString.isEmpty
     then EventStream.fromValue(div().ref)
     else
       val endpoint = diagramType match
         case DiagramType.Inheritance => "inheritance"
         case DiagramType.CallGraph   => "call-graph"

       fetchApiPath(s"$endpoint?path=$path").text.map: fetchResponse =>
         parser
           .parseFromString(fetchResponse.data, dom.MIMEType.`image/svg+xml`)
           .documentElement
    )
  }

def fetchSourceCode(paths: Signal[Path])(docPath: Path) =
  paths.flatMapSwitch: path =>
    fetchApiPath(
      s"${Endpoints.classes}?path=${encodeURIComponent(path.toString + "/" + docPath)}"
    ).text
      .map(response => response.data)
