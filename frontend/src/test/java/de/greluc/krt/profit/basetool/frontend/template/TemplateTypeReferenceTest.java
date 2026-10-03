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

import de.greluc.krt.profit.basetool.frontend.template.TemplateReferenceScan.TypeReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Resolves every SpEL {@code T(fqcn)} type reference of the templates statically (REQ-FE-026): the
 * class must load and the member must be a public static field or method of it. Without this a
 * moved or renamed {@code Roles} would fail the role-gated pages at render time only.
 */
class TemplateTypeReferenceTest {

  /** Selection floor: the {@code T(…)} references the templates held when the test was added. */
  private static final int MIN_REFERENCES = 172;

  /** Selection floor: the templates holding them. */
  private static final int MIN_TEMPLATES = 22;

  private final ClassLoader loader = getClass().getClassLoader();

  @Test
  void everyTypeReferenceOfEveryTemplateResolves() {
    List<TypeReference> references = new ArrayList<>();
    List<String> unparsed = new ArrayList<>();
    for (Map.Entry<String, String> template : TemplateReferenceScan.templates().entrySet()) {
      List<TypeReference> parsed =
          TemplateReferenceScan.typeReferences(template.getKey(), template.getValue());
      references.addAll(parsed);
      if (TemplateReferenceScan.looseTypeReferenceCount(template.getValue()) != parsed.size()) {
        unparsed.add(template.getKey());
      }
    }
    List<String> broken = new ArrayList<>();
    for (TypeReference reference : references) {
      String reason = TemplateReferenceScan.unresolved(reference, loader);
      if (reason != null) {
        broken.add(reference + " (" + reason + ")");
      }
    }

    assertThat(references)
        .as("selection floor: the scan must find at least today's T(...) references")
        .hasSizeGreaterThanOrEqualTo(MIN_REFERENCES);
    assertThat(references.stream().map(TypeReference::template).distinct().count())
        .as("selection floor: templates holding a T(...) reference")
        .isGreaterThanOrEqualTo(MIN_TEMPLATES);
    assertThat(unparsed)
        .as("templates with a T( opener the scan cannot parse; write it as T(fqcn).MEMBER")
        .isEmpty();
    assertThat(broken).as("T(...) references that fail at render time").isEmpty();
  }

  @Test
  void aMovedClassOrAMissingMemberIsReported() {
    String html =
        """
        <a sec:authorize="hasRole(T(de.greluc.krt.profit.basetool.frontend.support.Roles).ADMIN)"></a>
        <a sec:authorize="hasRole(T(de.greluc.krt.profit.basetool.frontend.kernel.Roles).ADMIN)"></a>
        <a sec:authorize="hasRole(T(de.greluc.krt.profit.basetool.frontend.support.Roles).NOPE)"></a>
        <a th:text="${T(de.greluc.krt.profit.basetool.frontend.support.Roles).authority('X')}"></a>
        <a th:text="${T(de.greluc.krt.profit.basetool.frontend.support.Roles).nope('X')}"></a>
        """;

    List<TypeReference> references = TemplateReferenceScan.typeReferences("fixture.html", html);

    assertThat(references).hasSize(5);
    assertThat(TemplateReferenceScan.looseTypeReferenceCount(html)).isEqualTo(5);
    assertThat(references.stream().map(r -> TemplateReferenceScan.unresolved(r, loader)).toList())
        .containsExactly(
            null,
            "class does not load",
            "no public static field NOPE",
            null,
            "no public static method nope");
  }

  @Test
  void anUnparseableOpenerIsCountedSoItCannotHide() {
    String html = "<a th:if=\"${T( 'not a type' ).X}\"></a>";

    assertThat(TemplateReferenceScan.typeReferences("fixture.html", html)).isEmpty();
    assertThat(TemplateReferenceScan.looseTypeReferenceCount(html)).isEqualTo(1);
  }
}
