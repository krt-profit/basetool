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

package de.greluc.krt.profit.basetool.frontend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaFieldAccess;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Asserts structurally that the frontend's single identity-less WebClient is used by exactly one
 * call site, the terms document read {@code getTermsDocumentAnonymously()} (REQ-SEC-052, ADR-0159).
 */
class TermsDocumentClientUsageTest {

  private static final String FIELD = "termsDocumentClient";

  private static final JavaClasses CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("de.greluc.krt.profit.basetool.frontend");

  /** Every declared field named {@code termsDocumentClient} across the frontend's own classes. */
  private static List<JavaField> declaredFields() {
    return CLASSES.stream()
        .flatMap(c -> c.getFields().stream())
        .filter(f -> FIELD.equals(f.getName()))
        .toList();
  }

  @Test
  void exactlyOneClassHoldsTheAnonymousClient() {
    List<JavaField> fields = declaredFields();

    assertThat(fields).as("the anonymous WebClient is injected in exactly one place").hasSize(1);
    assertThat(fields.get(0).getOwner().getSimpleName())
        .as("and that place is the single seam to the backend")
        .isEqualTo("BackendApiClient");
  }

  @Test
  void onlyGetTermsDocumentAnonymouslyReadsIt() {
    Set<String> readers =
        declaredFields().stream()
            .flatMap(f -> f.getAccessesToSelf().stream())
            .map(JavaFieldAccess::getOrigin)
            .map(origin -> origin.getOwner().getSimpleName() + "#" + origin.getName())
            .filter(name -> !name.endsWith("#<init>"))
            .collect(Collectors.toSet());

    assertThat(readers)
        .as("only the terms document may be fetched without a caller")
        .containsExactly("BackendApiClient#getTermsDocumentAnonymously");
  }
}
