/*
 * Profit Basetool - squadron-management web app.
 * Copyright (C) 2026 Lucas Greuloch
 *
 * SPDX-License-Identifier: GPL-3.0-only
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, version 3.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package de.greluc.krt.profit.basetool.frontend.catalogue.client;

import java.util.ArrayList;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * A backend URI template with its variables, built from a path and query parameters of which an
 * absent one is left out, so the backend reads it as "all". Every value travels as a URI variable,
 * so the client encodes it.
 */
final class QueryTemplate {

  private final StringBuilder template;
  private final List<Object> variables;
  private boolean hasQuery;

  /**
   * Starts a template at a path.
   *
   * @param path the path, which may hold its own {@code {name}} variables
   * @param pathVariables the values of the path's variables, in order
   */
  QueryTemplate(@NotNull String path, @NotNull Object... pathVariables) {
    this.template = new StringBuilder(path);
    this.variables = new ArrayList<>(List.of(pathVariables));
  }

  /**
   * Adds a query parameter unless its value is absent.
   *
   * @param name the parameter name, also the name of its URI variable
   * @param value the value, or {@code null} to leave the parameter out
   * @return this template
   */
  @NotNull
  QueryTemplate param(@NotNull String name, @Nullable Object value) {
    if (value != null) {
      template.append(hasQuery ? '&' : '?').append(name).append("={").append(name).append('}');
      variables.add(value);
      hasQuery = true;
    }
    return this;
  }

  /**
   * Returns the URI template.
   *
   * @return the path followed by the present parameters
   */
  @NotNull
  String template() {
    return template.toString();
  }

  /**
   * Returns the URI variables in template order.
   *
   * @return a new array of the path variables, then the present parameter values
   */
  @NotNull
  Object[] variables() {
    return variables.toArray();
  }
}
