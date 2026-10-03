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

package de.greluc.krt.profit.basetool.frontend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Keeps every entry of the session type allow-list no broader than a model or session package
 * (REQ-FE-027, ADR-0206): an application prefix must name a {@code model} or {@code session}
 * package of the frontend, the other prefixes and the name patterns stay exactly the reviewed ones,
 * and an exact name is a plain class name.
 */
class SessionTypeAllowListBreadthTest {

  /** The non-application prefixes admitted wholesale by review. */
  private static final Set<String> REVIEWED_FOREIGN_PREFIXES =
      Set.of("org.springframework.security.");

  /** The reviewed name patterns, as their source strings. */
  private static final Set<String> REVIEWED_PATTERNS =
      Set.of(
          "java\\.(?:util|time)\\.[\\w$]+",
          "java\\.lang\\.(?:Boolean|Byte|Character|Double|Float|Integer|Long|Short|String)",
          "org\\.springframework\\.validation\\.[\\w$]+");

  /**
   * An application prefix at least as narrow as a {@code model} or {@code session} package of the
   * frontend, e.g. {@code …frontend.model.} or {@code …frontend.mission.model.}.
   */
  private static final Pattern MODEL_OR_SESSION_PREFIX =
      Pattern.compile(
          "de\\.greluc\\.krt\\.profit\\.basetool\\.frontend\\.(?:[a-z0-9_]+\\.)*"
              + "(?:model|session)\\.(?:[a-z0-9_]+\\.)*");

  /** A plain binary class name. */
  private static final Pattern CLASS_NAME =
      Pattern.compile("[A-Za-z_][\\w]*(?:\\.[A-Za-z_][\\w]*)*(?:\\$[\\w]+)*");

  @Test
  void noPrefixIsBroaderThanAModelOrSessionPackage() {
    assertThat(SessionTypeAllowList.ALLOWED_PREFIXES).isNotEmpty();
    assertThat(tooBroad(SessionTypeAllowList.ALLOWED_PREFIXES)).isEmpty();
  }

  @Test
  void theNamePatternsAreExactlyTheReviewedOnes() {
    assertThat(
            Set.of(
                SessionTypeAllowList.JDK_VALUE_TYPES.pattern(),
                SessionTypeAllowList.JAVA_LANG_SCALARS.pattern(),
                SessionTypeAllowList.VALIDATION_TYPES.pattern()))
        .isEqualTo(REVIEWED_PATTERNS);
  }

  @Test
  void everyExactNameIsAPlainClassName() {
    List<String> names = new ArrayList<>(SessionTypeAllowList.ALLOWED_EXACT_NAMES);
    names.addAll(RedisSessionConfig.CONTAINER_WRITTEN_FINAL_SESSION_TYPES);

    assertThat(names).isNotEmpty().allMatch(name -> CLASS_NAME.matcher(name).matches());
  }

  @Test
  void aWidenedPrefixIsReported() {
    assertThat(
            tooBroad(
                List.of(
                    "de.greluc.krt.profit.basetool.frontend.model.",
                    "de.greluc.krt.profit.basetool.frontend.mission.model.",
                    "de.greluc.krt.profit.basetool.frontend.kernel.session.",
                    "de.greluc.krt.profit.basetool.frontend.",
                    "de.greluc.krt.profit.basetool.frontend.mission.",
                    "de.greluc.",
                    "java.",
                    "org.springframework.",
                    "org.springframework.security.",
                    "de.greluc.krt.profit.basetool.frontend.modelx.")))
        .containsExactly(
            "de.greluc.krt.profit.basetool.frontend.",
            "de.greluc.krt.profit.basetool.frontend.mission.",
            "de.greluc.",
            "java.",
            "org.springframework.",
            "de.greluc.krt.profit.basetool.frontend.modelx.");
  }

  @Test
  void aWildcardExactNameIsNotAPlainClassName() {
    assertThat(List.of("java.util.*", "java.", "java.util.concurrent.CopyOnWriteArrayList"))
        .filteredOn(name -> !CLASS_NAME.matcher(name).matches())
        .containsExactly("java.util.*", "java.");
  }

  /**
   * The prefixes broader than a model or session package of the frontend and not reviewed.
   *
   * @param prefixes the allow-list's prefixes
   * @return the offending prefixes, in input order
   */
  private static List<String> tooBroad(List<String> prefixes) {
    return prefixes.stream()
        .filter(prefix -> !REVIEWED_FOREIGN_PREFIXES.contains(prefix))
        .filter(prefix -> !MODEL_OR_SESSION_PREFIX.matcher(prefix).matches())
        .toList();
  }
}
