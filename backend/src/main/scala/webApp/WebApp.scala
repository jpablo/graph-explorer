package webApp

import backends.plantuml.PlantumlInheritance
import inheritance.InheritanceExamples
import zio.*
import zhttp.http.*
import zhttp.service.Server
import io.circe.syntax.*
import semanticdb.ClassesList
import java.nio.file.Paths
import java.net.URI

object WebApp extends ZIOAppDefault {

  val allowCors = "Access-Control-Allow-Origin" -> "*"

  val app = Http.collect[Request] {

    case req @ Method.GET -> !! / "classes" =>

      req.url.queryParams.get("path") match

        case r @ Some(h :: t) if h.nonEmpty =>
          val paths = Paths.get(h, t*)
          val namespaces = ClassesList.scan(paths)
          Response.json(namespaces.asJson.toString)
            .addHeader(allowCors)

        case _ =>
          Response.status(Status.BadRequest)

    case Method.GET -> !! / "inheritance" =>
      val plantUmlText = PlantumlInheritance.toDiagram(InheritanceExamples.laminar)
      val svgText = PlantumlInheritance.renderDiagram("laminar", plantUmlText)

      Response.text(svgText)
        .withContentType("image/svg+xml")
        .addHeader(allowCors)
  }

  val run =
    Server.start(8090, app)
}
