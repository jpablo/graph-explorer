package org.jpablo.graphexplorer.viewer.state

import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.scalajs.dom

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.{Future, Promise}
import scala.scalajs.js
import scala.util.hashing.MurmurHash3

/** Thumbnail SVG persisted across sessions, behind [[ThumbnailSvgCache]].
  *
  * The library's cost is not DRAWING thumbnails, it is LAYING THEM OUT: one
  * graph layout per card, indivisible once started, triggered by the
  * IntersectionObserver during the scroll that revealed the card. Measured on a
  * real library: one frame blocked 965ms with no forced layout at all (a
  * Graphviz layout), alongside twelve frames carrying 28-104ms of forced
  * style/layout each (Mermaid measuring text through the DOM — a getBBox after
  * an SVG text mutation costs ~1.1ms on the library page against ~11µs in an
  * isolated document, and no amount of CSS containment reduces it).
  *
  * Scheduling those renders on idle moved the work off the scroll's frames but
  * could not make it smaller. Storing the RESULT removes both causes at once: a
  * warm library parses SVG strings and lays out nothing. Only the first sighting
  * of a given source pays.
  *
  * IndexedDB rather than localStorage on purpose. localStorage is synchronous —
  * reading a megabyte of SVG on the main thread would reintroduce, as a blocking
  * read, the exact stall this exists to remove. It is also a database of its
  * own, disjoint from the project data in localStorage (ProjectsStorage.rawKey):
  * nothing here can corrupt a diagram, and dropping the whole store is always
  * safe — the worst case is one slow scroll while it refills.
  */
private[state] object ThumbnailDiskCache:

  private val DbName     = "graph-explorer-thumbnails"
  private val StoreName  = "thumbs"
  private val TimeIndex  = "at"
  private val MaxEntries = 128

  private type Handler = js.Function1[js.Dynamic, Unit]

  /** Opened once. A `Future[None]` is a permanent, deliberate answer: private
    * browsing and quota-denied origins never become usable, so every later call
    * degrades to a miss instead of retrying an open that cannot succeed. */
  private var dbF: Option[Future[Option[js.Dynamic]]] = None

  private def db(): Future[Option[js.Dynamic]] =
    dbF match
      case Some(f) => f
      case None    =>
        val f = openDb()
        dbF = Some(f)
        f

  private def openDb(): Future[Option[js.Dynamic]] =
    val idb = dom.window.asInstanceOf[js.Dynamic].indexedDB
    if js.isUndefined(idb) || idb == null then Future.successful(None)
    else
      val p = Promise[Option[js.Dynamic]]()
      try
        val req = idb.open(DbName, 1)
        req.onupgradeneeded = ((_: js.Dynamic) =>
          val database = req.result.asInstanceOf[js.Dynamic]
          if !database.objectStoreNames.contains(StoreName).asInstanceOf[Boolean] then
            val store = database.createObjectStore(StoreName, js.Dynamic.literal(keyPath = "k"))
            // Eviction walks records oldest-first; without the index it would
            // have to load every stored SVG just to find which to drop.
            store.createIndex(TimeIndex, TimeIndex, js.Dynamic.literal(unique = false))
        ): Handler
        req.onsuccess = ((_: js.Dynamic) => p.trySuccess(Some(req.result.asInstanceOf[js.Dynamic])): Unit): Handler
        req.onerror = ((_: js.Dynamic) => p.trySuccess(None): Unit): Handler
        req.onblocked = ((_: js.Dynamic) => p.trySuccess(None): Unit): Handler
      catch case _: Throwable => p.trySuccess(None)
      p.future

  /** The stored SVG for `source`, if this browser has laid it out before. */
  def get(format: DiagramFormat, source: String): Future[Option[String]] =
    db().flatMap {
      case None => Future.successful(None)
      case Some(database) =>
        val p = Promise[Option[String]]()
        try
          val req = database.transaction(StoreName, "readonly").objectStore(StoreName).get(keyFor(format, source))
          req.onsuccess = ((_: js.Dynamic) =>
            val rec = req.result
            // The key carries a 32-bit hash, so the SOURCE is verified before
            // the record is trusted — a collision would otherwise serve one
            // diagram's picture for another's. Same guard ThumbnailSvgCache
            // applies in memory.
            val hit =
              if js.isUndefined(rec) || rec == null then None
              else if rec.source.asInstanceOf[String] == source then Option(rec.svg.asInstanceOf[String])
              else None
            p.trySuccess(hit): Unit
          ): Handler
          req.onerror = ((_: js.Dynamic) => p.trySuccess(None): Unit): Handler
        catch case _: Throwable => p.trySuccess(None)
        p.future
    }.recover { case _ => None }

  /** Store a freshly rendered thumbnail. Fire-and-forget: a failed write costs
    * one re-render next session and nothing else. */
  def put(format: DiagramFormat, source: String, svgHtml: String): Unit =
    db().foreach {
      case None => ()
      case Some(database) =>
        try
          val tx = database.transaction(StoreName, "readwrite")
          tx.objectStore(StoreName)
            .put(js.Dynamic.literal(k = keyFor(format, source), source = source, svg = svgHtml, at = js.Date.now()))
          tx.oncomplete = ((_: js.Dynamic) => evict(database)): Handler
        catch case _: Throwable => ()
    }

  /** Drop the oldest records once the store outgrows [[MaxEntries]]. Thumbnails
    * are whole SVG documents, so an unbounded store would grow into the origin's
    * quota and start failing writes for everything sharing it. */
  private def evict(database: js.Dynamic): Unit =
    try
      val store   = database.transaction(StoreName, "readwrite").objectStore(StoreName)
      val countRq = store.count()
      countRq.onsuccess = ((_: js.Dynamic) =>
        var remaining = countRq.result.asInstanceOf[Int] - MaxEntries
        if remaining > 0 then
          val cursorRq = store.index(TimeIndex).openCursor()
          cursorRq.onsuccess = ((_: js.Dynamic) =>
            val cursor = cursorRq.result
            if !js.isUndefined(cursor) && cursor != null && remaining > 0 then
              cursor.delete()
              remaining -= 1
              cursor.continue()
          ): Handler
      ): Handler
    catch case _: Throwable => ()

  /** [[ThumbnailSvgCache]]'s key, flattened to a string so IndexedDB can use it
    * as a primary key. */
  private def keyFor(format: DiagramFormat, source: String): String =
    s"${format.toString}:${MurmurHash3.stringHash(source)}:${source.length}"
