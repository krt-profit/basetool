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

package de.greluc.krt.profit.basetool.backend.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.server.PathContainer;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Ties the committed retired-operation list to the API it retires from (REQ-API-020): it is dormant
 * while empty, no entry shadows an operation the backend still serves, and every entry is a break
 * the declared-break ledger records.
 */
class RetiredOperationsContractTest {

  /** The committed OpenAPI document. */
  private static final String OPENAPI_RESOURCE = "/api/openapi.json";

  /** The declared-break ledger on the test classpath (REQ-API-017). */
  private static final String LEDGER_RESOURCE = "api/declared-breaks.txt";

  /** The verbs an OpenAPI path item may carry that an entry can name. */
  private static final List<String> VERBS = List.of("get", "put", "post", "delete", "patch");

  /** The documented operations on 2026-10-03; a smaller sweep would assert less. */
  private static final int OPERATION_FLOOR = 550;

  /**
   * Reads every documented operation as {@code VERB path}.
   *
   * @return the operations, path templates as documented
   */
  static List<String> documentedOperations() {
    JsonNode document;
    try (InputStream in =
        RetiredOperationsContractTest.class.getResourceAsStream(OPENAPI_RESOURCE)) {
      assertThat(in).as("%s must be on the test classpath", OPENAPI_RESOURCE).isNotNull();
      document = new ObjectMapper().readTree(in);
    } catch (IOException e) {
      throw new IllegalStateException("could not read " + OPENAPI_RESOURCE, e);
    }
    List<String> operations = new ArrayList<>();
    JsonNode paths = document.path("paths");
    for (String path : paths.propertyNames()) {
      for (String verb : VERBS) {
        if (!paths.path(path).path(verb).isMissingNode()) {
          operations.add(verb.toUpperCase(Locale.ROOT) + " " + path);
        }
      }
    }
    return operations;
  }

  /**
   * Lists the documented operations a retired entry would answer in place of the handler.
   *
   * @param retired the retired operations
   * @param operations the documented operations, {@code VERB path}
   * @return the shadowed operations
   */
  static List<String> shadowed(RetiredOperations retired, List<String> operations) {
    List<String> shadowed = new ArrayList<>();
    for (String operation : operations) {
      int space = operation.indexOf(' ');
      String verb = operation.substring(0, space);
      String concrete = operation.substring(space + 1).replaceAll("\\{[^}]+}", "x");
      if (retired.match(verb, PathContainer.parsePath(concrete)).isPresent()) {
        shadowed.add(operation);
      }
    }
    return shadowed;
  }

  /**
   * Lists the retired entries the ledger does not declare; an entry is declared when a ledger line
   * names its verb and path template and the whole operation ({@code -}) as the broken field.
   *
   * @param retired the retired operations
   * @param ledger the ledger's lines
   * @return the undeclared entries
   */
  static List<String> undeclared(RetiredOperations retired, List<String> ledger) {
    List<String> undeclared = new ArrayList<>();
    for (RetiredOperations.Entry entry : retired.entries()) {
      String needle = entry.method() + " " + entry.path();
      boolean declared =
          ledger.stream().map(String::strip).anyMatch(line -> line.startsWith(needle + " - "));
      if (!declared) {
        undeclared.add(needle);
      }
    }
    return undeclared;
  }

  /**
   * Loads the committed list.
   *
   * @return the production list
   * @throws IOException if it cannot be read
   */
  private static RetiredOperations committed() throws IOException {
    return new RetiredOperationsConfig().retiredOperations();
  }

  @Test
  @DisplayName("the sweep reads every documented operation")
  void theSweepReadsEveryDocumentedOperation() {
    assertThat(documentedOperations()).hasSizeGreaterThanOrEqualTo(OPERATION_FLOOR);
  }

  @Test
  @DisplayName("no committed entry shadows an operation the backend still serves")
  void noEntryShadowsALiveOperation() throws IOException {
    assertThat(shadowed(committed(), documentedOperations()))
        .as(
            "a retired entry answers ahead of authentication; on a live operation it would make"
                + " the handler unreachable")
        .isEmpty();
  }

  @Test
  @DisplayName("control: the shadow check finds a planted entry on a live operation")
  void theShadowCheckFindsAPlantedEntry() {
    RetiredOperations planted = RetiredOperations.parse(List.of("GET /api/v1/missions/{id}"));
    assertThat(shadowed(planted, documentedOperations())).contains("GET /api/v1/missions/{id}");
  }

  @Test
  @DisplayName("every committed entry is a break the declared-break ledger records")
  void everyEntryIsDeclaredInTheLedger() throws IOException {
    RetiredOperations retired = committed();
    ClassPathResource ledger = new ClassPathResource(LEDGER_RESOURCE);
    assertThat(ledger.exists())
        .as("the declared-break ledger %s is committed", LEDGER_RESOURCE)
        .isTrue();
    List<String> lines =
        new String(ledger.getContentAsByteArray(), StandardCharsets.UTF_8).lines().toList();
    assertThat(undeclared(retired, lines)).isEmpty();
  }

  @Test
  @DisplayName("control: the ledger check finds a planted undeclared entry")
  void theLedgerCheckFindsAPlantedEntry() {
    RetiredOperations planted =
        RetiredOperations.parse(List.of("GET /api/v1/old/{id}", "DELETE /api/v1/older"));
    assertThat(
            undeclared(
                planted,
                List.of("GET /api/v1/old/{id} - 18", "DELETE /api/v1/older Older.name 18")))
        .as("a field-level break does not retire its operation")
        .containsExactly("DELETE /api/v1/older");
  }

  @Test
  @DisplayName("while the committed list is empty no documented operation meets the wall")
  void anEmptyListIsDormantForEveryOperation() throws Exception {
    RetiredOperations retired = committed();
    if (!retired.isEmpty()) {
      return;
    }
    RetiredOperationFilter filter = new RetiredOperationFilterTest().filter(retired);
    int passed = 0;
    for (String operation : documentedOperations()) {
      int space = operation.indexOf(' ');
      MockFilterChain chain = new MockFilterChain();
      MockHttpServletResponse response =
          RetiredOperationFilterTest.run(
              filter,
              operation.substring(0, space),
              operation.substring(space + 1).replaceAll("\\{[^}]+}", "x"),
              chain);
      assertThat(chain.getRequest()).as(operation).isNotNull();
      assertThat(response.getStatus()).as(operation).isEqualTo(200);
      passed++;
    }
    assertThat(passed).isGreaterThanOrEqualTo(OPERATION_FLOOR);
  }
}
