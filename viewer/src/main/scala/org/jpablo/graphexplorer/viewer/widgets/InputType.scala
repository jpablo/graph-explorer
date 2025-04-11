package org.jpablo.graphexplorer.viewer.widgets

import com.raquo.laminar.api.L.*

enum MenuDirection derives CanEqual:
  case start, end //, center, top, bottom, left, right

enum InputType derives CanEqual:
  case select, text, multiText, color, checkbox, radio, file, hidden, password, submit, reset, button, image,
    datetime, datetimeLocal, date, month, time, week, url, email, search, tel
  case number(start: Option[Double] = None, end: Option[Double] = None, step: Option[Double] = None)
  case range(start: Option[Double] = None, end: Option[Double] = None, step: Option[Double] = None)
  case menuWithExtra(initial: Int, dir: MenuDirection = MenuDirection.start, cardClass: Option[String] = None)
  case currentValueWithSelector(dir: MenuDirection = MenuDirection.start, cardClass: Option[String] = None)
