package org.jpablo.graphexplorer.viewer.state

import com.raquo.laminar.api.L.*
import com.raquo.laminar.nodes.ReactiveSvgElement
import org.jpablo.graphexplorer.viewer.backends.DiagramFormat
import org.scalajs.dom.svg.SVG

import scala.collection.mutable
import scala.util.hashing.MurmurHash3

/** In-memory cache for SVG thumbnail DOM prototypes.
  *
  * Important: callers must always mount clones, never the cached prototype itself.
  */
private[state] object ThumbnailSvgCache:
  private case class Key(format: DiagramFormat, hash: Int, len: Int) derives CanEqual
  private case class Entry(source: String, proto: dom.svg.SVG) derives CanEqual

  // Keep this bounded: thumbnails can be large SVG trees.
  private val maxEntries = 64
  private val cache      = mutable.LinkedHashMap.empty[Key, Entry]

  def size: Int = cache.size

  def get(format: DiagramFormat, source: String): Option[dom.svg.SVG] =
    val key = keyFor(format, source)
    cache.get(key).filter(_.source == source).map: entry =>
      touch(key, entry)
      entry.proto

  def put(format: DiagramFormat, source: String, proto: dom.svg.SVG): Unit =
    val key   = keyFor(format, source)
    val entry = Entry(source, proto)
    touch(key, entry)
    evictIfNeeded()

  def cloneSvg(proto: dom.svg.SVG): ReactiveSvgElement[SVG] =
    val cloned = proto.cloneNode(deep = true).asInstanceOf[dom.svg.SVG]
    foreignSvgElement(svg.svg, cloned)

  private def keyFor(format: DiagramFormat, source: String): Key =
    Key(format, MurmurHash3.stringHash(source), source.length)

  private def touch(key: Key, entry: Entry): Unit =
    cache.remove(key)
    cache.put(key, entry)

  private def evictIfNeeded(): Unit =
    while cache.size > maxEntries do
      cache.remove(cache.head._1)

