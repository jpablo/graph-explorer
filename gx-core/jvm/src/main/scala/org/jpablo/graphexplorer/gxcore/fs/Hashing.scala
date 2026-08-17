package org.jpablo.graphexplorer.gxcore.fs

import org.jpablo.graphexplorer.gxcore.model.ContentHash

import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/** Computing a [[ContentHash]]. Comparing them is pure and lives in the model;
  * producing one needs a platform, which is why this is on the JVM side.
  *
  * SHA-256 rather than the BLAKE3 named in D1. BLAKE3 is faster, but it is a
  * dependency that would have to survive both GraalVM native-image and a later
  * Scala.js cross-build, whereas `MessageDigest` ships with the JDK and P0
  * proved it works under native-image on all three platforms. Diagram files are
  * kilobytes; the speed difference is not observable, and the dependency risk is.
  * Revisit if hashing ever shows up in a profile.
  */
object Hashing:

  /** Hash raw bytes. Documents hash their bytes ON DISK, not their decoded text:
    * the bytes are what two processes can agree on without first agreeing on an
    * encoding and a line-ending convention.
    */
  def ofBytes(bytes: Array[Byte]): ContentHash =
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val hex    = StringBuilder(digest.length * 2)
    for b <- digest do hex.append(f"${b & 0xff}%02x")
    ContentHash.fromHex(hex.toString)

  /** Hash text as it would be stored: UTF-8, with the given line ending.
    *
    * Both arguments are required rather than defaulted on purpose. A caller that
    * hashes text without saying how it will be written can produce a value that
    * disagrees with the file it is supposed to describe — which under D1 means a
    * phantom conflict, the least debuggable failure this design has. See V-16
    * and [[LineEnding]].
    */
  def ofText(text: String, lineEnding: LineEnding): ContentHash =
    ofBytes(lineEnding.applyTo(text).getBytes(StandardCharsets.UTF_8))
