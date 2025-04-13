package org.jpablo.graphexplorer.viewer.models

case class Lens[A, B](
    get:    A => B,
    update: (A, B) => A
)
