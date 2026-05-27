package org.jpablo.graphexplorer.graphviz.layout

/** Network-simplex balancing mode, mirroring `ns.c`'s `balance` parameter.
  *
  *  - [[None]] — no post-solve balancing (raw NS ranks).
  *  - [[TopBottom]] — `TB_balance`: spread loose nodes across their feasible
  *    rank window (used by [[Rank]] for the main rank assignment).
  *  - [[LeftRight]] — `LR_balance`: centre each component within its
  *    zero-cutvalue slack (used by [[XCoord]] for the x-coordinate solve).
  */
enum NSBalance(val ord: Int) derives CanEqual:
  case None       extends NSBalance(0)
  case TopBottom  extends NSBalance(1)
  case LeftRight  extends NSBalance(2)
