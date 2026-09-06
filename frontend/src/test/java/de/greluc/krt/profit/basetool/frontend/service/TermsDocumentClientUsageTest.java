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
 * The frontend has exactly one WebClient that carries no caller identity, and exactly one call site
 * allowed to use it (REQ-SEC-052, ADR-0159).
 *
 * <p>Its predecessor was called {@code publicWebClient} and was selected by an {@code isPublic}
 * boolean passed at roughly forty call sites — the mission list, the order queue, the catalogue
 * pickers, the home page. Every one of those was a decision to send a request with no identity,
 * taken by typing {@code true} into an argument list, and none of them was reviewable as such: a
 * boolean is the wrong shape for "this request has no caller". The flag is gone and the client is
 * named for its one job, but a name alone does not stop the next person from injecting the bean
 * somewhere else. This test does.
 *
 * <p>Deliberately structural rather than behavioural. What it protects is not what {@code
 * getTermsDocumentAnonymously()} returns — {@code BackendApiClientHappyPathTest} covers that — but
 * that the surface stays a single door: one field, one reader, and it is the terms document.
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

    // The constructor assignment is filtered out above; what is left is every method that reads the
    // field. One entry, and it is the named one — a second would mean a second anonymous call.
    assertThat(readers)
        .as("only the terms document may be fetched without a caller")
        .containsExactly("BackendApiClient#getTermsDocumentAnonymously");
  }
}
