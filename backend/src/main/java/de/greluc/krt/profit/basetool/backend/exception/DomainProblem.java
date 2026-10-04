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

package de.greluc.krt.profit.basetool.backend.exception;

import java.util.Locale;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpStatus;

/**
 * The open base every module's exceptions extend from the module's own package (ADR-0235).
 *
 * <p>A subclass names its status, its code (from its module's {@link ProblemCode} enum or its
 * module's frozen registry) and its log label; the {@code type} suffix and the bundle keys follow
 * from the code: {@code problem.<code in lower case>.title} and {@code .detail}, and the code in
 * lower case with hyphens. The disclosure policy is {@link ErrorDisclosurePolicy#STANDARD}.
 */
public abstract non-sealed class DomainProblem extends AppException {

  /**
   * Creates a module exception.
   *
   * @param message verbatim {@code detail} or an i18n key
   */
  protected DomainProblem(String message) {
    super(message);
  }

  /**
   * Creates a module exception wrapping a cause kept for server-side logging only.
   *
   * @param message verbatim {@code detail} or an i18n key
   * @param cause underlying failure that triggered this exception
   */
  protected DomainProblem(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * The HTTP status the RFC&nbsp;7807 response carries.
   *
   * @return the status of this exception
   */
  @Override
  public abstract HttpStatus status();

  /**
   * The stable, machine-readable {@code code} extension property.
   *
   * @return the code of this exception
   */
  @Override
  public abstract String code();

  /**
   * The short phrase the dispatch handler's log line names this exception by.
   *
   * @return the log label of this exception
   */
  @Override
  public abstract String logLabel();

  /**
   * The {@code type} suffix: the code in lower case with hyphens instead of underscores.
   *
   * @return the problem-type suffix derived from {@link #code()}
   */
  @NotNull
  @Override
  public String typeSuffix() {
    return code().toLowerCase(Locale.ROOT).replace('_', '-');
  }

  /**
   * The {@code title} bundle key, {@code problem.<code in lower case>.title}.
   *
   * @return the title bundle key derived from {@link #code()}
   */
  @NotNull
  @Override
  public String titleKey() {
    return keyBase() + ".title";
  }

  /**
   * The {@code detail} bundle key, {@code problem.<code in lower case>.detail}.
   *
   * @return the detail bundle key derived from {@link #code()}
   */
  @NotNull
  @Override
  public String detailKey() {
    return keyBase() + ".detail";
  }

  /**
   * The bundle-key prefix the title and detail keys extend.
   *
   * @return {@code problem.<code in lower case>}
   */
  @NotNull
  private String keyBase() {
    return "problem." + code().toLowerCase(Locale.ROOT);
  }
}
