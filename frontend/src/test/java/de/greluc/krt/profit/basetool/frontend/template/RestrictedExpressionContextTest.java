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

package de.greluc.krt.profit.basetool.frontend.template;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Thymeleaf evaluates {@code th:attr} in a restricted expression mode: {@code T(…)} and {@code new}
 * throw at render time, and only for a row that reaches the attribute. This test finds them
 * statically, so a page that renders without data cannot hide one.
 */
class RestrictedExpressionContextTest {

  private static final Pattern TH_ATTR = Pattern.compile("th:attr=\"([^\"]*)\"");

  private static final Pattern FORBIDDEN = Pattern.compile("(?<![\\w.])(T\\(|new\\s+[A-Za-z_])");

  @Test
  void noThAttrUsesATypeReferenceOrAnInstantiation() {
    List<String> offenders = new ArrayList<>();
    int attributes = 0;
    for (Map.Entry<String, String> template : TemplateReferenceScan.templates().entrySet()) {
      Matcher matcher = TH_ATTR.matcher(template.getValue());
      while (matcher.find()) {
        attributes++;
        if (FORBIDDEN.matcher(matcher.group(1)).find()) {
          offenders.add(template.getKey() + ": " + matcher.group(1));
        }
      }
    }

    assertThat(attributes).as("selection floor: th:attr occurrences").isGreaterThan(100);
    assertThat(offenders)
        .as("th:attr expressions that fail with a restricted-context error at render time")
        .isEmpty();
  }

  @Test
  void aTypeReferenceInAThAttrIsFound() {
    Matcher matcher = TH_ATTR.matcher("<i th:attr=\"data-w=${T(java.lang.Math).round(x)}\"></i>");

    assertThat(matcher.find()).isTrue();
    assertThat(FORBIDDEN.matcher(matcher.group(1)).find()).isTrue();
  }
}
