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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.config.OpenApiProblemDetailsConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The error-code registry (REQ-API-019, ADR-0235): every code is registered once, the registry
 * equals the committed list, every code the handler, the filters and the exceptions produce is
 * registered, no code is written as a literal outside the registry, and the document lists them.
 */
class ProblemCodeRegistryTest {

  /** The committed registry list on the test classpath. */
  private static final String LIST_RESOURCE = "/api/problem-codes.txt";

  /** Where the main sources are, relative to the repository root. */
  private static final String MAIN_SOURCES = "backend/src/main/java";

  /** How many codes the registry held when the floor was last raised. */
  private static final int REGISTRY_FLOOR = 49;

  /** A fixture registry enum that repeats a kernel code. */
  enum FixtureClashingCodes implements ProblemCode {
    /** Clashes with the kernel's {@code NOT_FOUND}. */
    NOT_FOUND;

    /**
     * Returns the wire value.
     *
     * @return the constant's name
     */
    @Override
    public String code() {
      return name();
    }

    /**
     * Returns the status.
     *
     * @return {@code 404}
     */
    @Override
    public HttpStatus status() {
      return HttpStatus.NOT_FOUND;
    }
  }

  /** The registry is found, holds unique codes, and no code clashes with the exchange's. */
  @Test
  @DisplayName("every registered code is unique, also against the frozen exchange codes")
  void everyCodeIsUnique() {
    List<Class<?>> enums = ProblemCodeRegistry.registryEnums();
    assertThat(enums).as("no ProblemCode enum found, which would make this vacuous").isNotEmpty();

    Map<String, List<String>> codes = ProblemCodeRegistry.codes(enums);
    assertThat(codes).hasSizeGreaterThanOrEqualTo(REGISTRY_FLOOR);
    assertThat(duplicates(codes)).as("a code is declared twice").isEmpty();

    Set<String> exchange = ProblemCodeRegistry.constantCodes(ExchangeProblemException.class, "");
    assertThat(exchange).as("the exchange's frozen registry was not found").hasSizeGreaterThan(5);
    assertThat(new TreeSet<>(codes.keySet()))
        .as("a registered code clashes with a code of the frozen exchange registry")
        .doesNotContainAnyElementsOf(exchange);
  }

  /** A planted enum repeating a code is caught. */
  @Test
  @DisplayName("a planted enum repeating a code is caught")
  void aRepeatedCodeIsCaught() {
    Map<String, List<String>> codes =
        ProblemCodeRegistry.codes(List.of(CoreProblemCode.class, FixtureClashingCodes.class));

    assertThat(duplicates(codes)).containsOnlyKeys("NOT_FOUND");
  }

  /**
   * The registry equals the committed list, code and status, in both directions.
   *
   * @throws IOException if the list cannot be read
   */
  @Test
  @DisplayName("the registry equals the committed list of codes and statuses")
  void theRegistryEqualsTheCommittedList() throws IOException {
    Map<String, String> registered = new TreeMap<>();
    for (Class<?> type : ProblemCodeRegistry.registryEnums()) {
      for (Object constant : type.getEnumConstants()) {
        ProblemCode code = (ProblemCode) constant;
        registered.put(code.code(), String.valueOf(code.status().value()));
      }
    }

    Map<String, String> committed = new TreeMap<>();
    try (InputStream in = ProblemCodeRegistryTest.class.getResourceAsStream(LIST_RESOURCE)) {
      assertThat(in).as("%s must be on the classpath", LIST_RESOURCE).isNotNull();
      for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R")) {
        if (!line.isBlank()) {
          String[] tokens = line.strip().split(" ");
          committed.put(tokens[0], tokens[1]);
        }
      }
    }

    assertThat(registered)
        .as(
            "the problem-code registry and %s differ. A new, renamed or removed code is a contract"
                + " change for every client that compares codes; change the list in the same PR"
                + " so the change is reviewed (REQ-API-019)",
            LIST_RESOURCE)
        .isEqualTo(committed);
  }

  /**
   * Every code the exceptions, the handler and the filters produce is registered.
   *
   * @throws ClassNotFoundException if a named producer class is gone
   */
  @Test
  @DisplayName("every code the handler, the filters and the exceptions produce is registered")
  void everyProducedCodeIsRegistered() throws ClassNotFoundException {
    Set<String> registered =
        ProblemCodeRegistry.codes(ProblemCodeRegistry.registryEnums()).keySet();

    Set<String> produced = new TreeSet<>();
    for (AppExceptionKind kind : AppExceptionKind.values()) {
      produced.add(kind.code());
    }
    for (String type :
        List.of(
            "de.greluc.krt.profit.basetool.backend.exception.GlobalExceptionHandler",
            "de.greluc.krt.profit.basetool.backend.exception.BankConflictException",
            "de.greluc.krt.profit.basetool.backend.config.TermsAcceptanceAccessFilter",
            "de.greluc.krt.profit.basetool.backend.config.PendingApprovalAccessFilter",
            "de.greluc.krt.profit.basetool.backend.config.ActingMemberFilter",
            "de.greluc.krt.profit.basetool.backend.config.IdentityProviderUnavailableFilter",
            "de.greluc.krt.profit.basetool.backend.config.SubjectRateLimitingFilter",
            "de.greluc.krt.profit.basetool.backend.filter.RateLimitingFilter")) {
      Set<String> constants = ProblemCodeRegistry.constantCodes(Class.forName(type), "CODE_");
      assertThat(constants).as("%s declares no CODE_ constant any more", type).isNotEmpty();
      produced.addAll(constants);
    }

    assertThat(produced).hasSizeGreaterThanOrEqualTo(45);
    assertThat(registered)
        .as("a code the backend produces is not in the registry (REQ-API-019)")
        .containsAll(produced);
  }

  /** No main source writes a code as a literal; every code goes through the registry. */
  @Test
  @DisplayName("no main source writes a problem code as a string literal")
  void noCodeIsWrittenAsALiteral() {
    Map<String, Set<String>> literals =
        ProblemCodeRegistry.literalCodesIn(repositoryRoot().resolve(MAIN_SOURCES));

    assertThat(literals)
        .as(
            "a problem code is written as a string literal. Add it to CoreProblemCode (or the"
                + " module's ProblemCode enum) and reference the constant, so the registry and the"
                + " documented list stay complete (REQ-API-019)")
        .isEmpty();
  }

  /** The literal scanner recognises every site shape it guards. */
  @Test
  @DisplayName("the literal scanner finds each planted code site")
  void theLiteralScannerFindsPlantedSites() {
    String planted =
        "pd.setProperty(\"code\", \"PLANTED_ONE\");\n"
            + "static final String CODE_X = \"PLANTED_TWO\";\n"
            + "+ \"\\\"code\\\":\\\"PLANTED_THREE\\\",\\\"instance\\\":\"\n"
            + "problem(\"problem.x.title\", \"problem.x.detail\", \"planted-four\","
            + " \"PLANTED_FOUR\");\n"
            + "pd.setProperty(\"code\", CoreProblemCode.NOT_FOUND.code());\n";

    assertThat(ProblemCodeRegistry.literalCodes(planted))
        .containsExactlyInAnyOrder("PLANTED_ONE", "PLANTED_TWO", "PLANTED_THREE", "PLANTED_FOUR");
  }

  /**
   * The committed document lists exactly the registered codes on {@code ProblemDetail.code}, and
   * documents {@code correlationId} and {@code fieldErrors}.
   *
   * @throws IOException if the document cannot be read
   */
  @Test
  @DisplayName("the document's ProblemDetail lists the registered codes and the contract fields")
  void theDocumentListsTheCodes() throws IOException {
    JsonNode problem;
    try (InputStream in = ProblemCodeRegistryTest.class.getResourceAsStream("/api/openapi.json")) {
      problem =
          new ObjectMapper()
              .readTree(in)
              .path("components")
              .path("schemas")
              .path(OpenApiProblemDetailsConfig.PROBLEM_SCHEMA);
    }
    Set<String> documented = new TreeSet<>();
    problem
        .path("properties")
        .path("code")
        .path(OpenApiProblemDetailsConfig.CODES_EXTENSION)
        .forEach(code -> documented.add(code.asString()));

    assertThat(documented)
        .isEqualTo(
            new TreeSet<>(ProblemCodeRegistry.codes(ProblemCodeRegistry.registryEnums()).keySet()));
    assertThat(problem.path("properties").propertyNames())
        .contains("code", "correlationId", "fieldErrors", "errors", "type", "title", "status");
    assertThat(problem.path("required").isMissingNode() || !hasCodeRequired(problem))
        .as("code must stay optional: a required code would be frozen by the contract guards")
        .isTrue();
    assertThat(problem.path("properties").path("code").has("enum"))
        .as("code is a documented list, never an enum (theContractRequiredEnumsAreFrozen)")
        .isFalse();
  }

  /**
   * Tells whether a schema lists {@code code} as required.
   *
   * @param schema the schema node
   * @return whether {@code required} names {@code code}
   */
  private static boolean hasCodeRequired(JsonNode schema) {
    for (JsonNode name : schema.path("required")) {
      if ("code".equals(name.asString())) {
        return true;
      }
    }
    return false;
  }

  /**
   * Keeps the codes declared more than once.
   *
   * @param codes code to declaring constants
   * @return the codes with two or more declarations
   */
  private static Map<String, List<String>> duplicates(Map<String, List<String>> codes) {
    Map<String, List<String>> duplicates = new TreeMap<>();
    codes.forEach(
        (code, declarations) -> {
          if (declarations.size() > 1) {
            duplicates.put(code, new ArrayList<>(declarations));
          }
        });
    return duplicates;
  }

  /**
   * Walks up from the working directory to the repository root.
   *
   * @return the directory holding {@code settings.gradle.kts}
   */
  private static Path repositoryRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    assertThat(dir).as("repository root not found").isNotNull();
    return dir;
  }
}
