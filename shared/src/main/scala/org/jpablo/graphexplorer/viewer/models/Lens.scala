package org.jpablo.graphexplorer.viewer.models

case class Lens[A, B](
    in:  A => B,
    out: (A, B) => A
)
