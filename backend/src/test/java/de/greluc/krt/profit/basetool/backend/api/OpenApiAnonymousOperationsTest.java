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

package de.greluc.krt.profit.basetool.backend.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * What the committed {@code openapi.json} says about who may call each operation.
 *
 * <p><strong>Two defects this exists for, both of which shipped.</strong>
 *
 * <p>The first: twenty controllers declared {@code @SecurityRequirement(name = "bearerAuth")} while
 * {@code OpenApiConfig} defines the scheme as {@code bearer-jwt}. A name that resolves to nothing
 * is not an error in OpenAPI — it is an operation-level override that replaces the global
 * requirement with a reference to a scheme the document does not describe. 108 operations carried
 * it, and the Android app's generated client froze it. Nothing failed; the document simply stopped
 * saying that those operations need a token.
 *
 * <p>The second: <em>zero</em> operations declared {@code security: []}, so the two that genuinely
 * answer without one (REQ-SEC-052) were documented as requiring a bearer. That is the same defect
 * pointing the other way — a generated client attaches a token it may not have yet on exactly the
 * two calls a caller makes <em>before</em> it can have one.
 *
 * <p>The document is read from the classpath rather than regenerated here: it is the committed
 * artefact every downstream consumer reads (the Android contract sync, {@code generateApiTypes}),
 * and asserting on a freshly generated one would pass while the committed copy stayed wrong.
 */
class OpenApiAnonymousOperationsTest {

  /** The committed document, as it ships. */
  private static final String OPENAPI_RESOURCE = "/api/openapi.json";

  /**
   * The operations REQ-SEC-052 serves without a token, spelled {@code METHOD path}.
   *
   * <p>Exactly two, and the list is the requirement rather than a description of it: an operation
   * that gains an empty {@code security} list without appearing here is a widening of the public
   * surface that nobody wrote down.
   */
  private static final Set<String> ANONYMOUS_OPERATIONS =
      Set.of("get /api/v1/app/version-policy", "get /api/v1/terms/document");

  /** The HTTP verbs an OpenAPI path item may carry. */
  private static final List<String> VERBS =
      List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

  /**
   * Reads the committed document.
   *
   * @return the parsed document
   */
  private static JsonNode openapi() {
    try (InputStream in =
        OpenApiAnonymousOperationsTest.class.getResourceAsStream(OPENAPI_RESOURCE)) {
      assertThat(in)
          .as("the committed %s must be on the test classpath", OPENAPI_RESOURCE)
          .isNotNull();
      return new ObjectMapper().readTree(in);
    } catch (Exception e) {
      throw new IllegalStateException("could not read " + OPENAPI_RESOURCE, e);
    }
  }

  /**
   * Every security scheme the document defines.
   *
   * @param document the parsed document
   * @return the scheme names under {@code components.securitySchemes}
   */
  private static Set<String> definedSchemes(JsonNode document) {
    Set<String> names = new LinkedHashSet<>();
    document.path("components").path("securitySchemes").propertyNames().forEach(names::add);
    return names;
  }

  @Test
  @DisplayName("no operation references a security scheme the document does not define")
  void everyReferencedSchemeIsDefined() {
    JsonNode document = openapi();
    Set<String> defined = definedSchemes(document);
    assertThat(defined)
        .as("the document must define at least the bearer scheme it globally requires")
        .contains("bearer-jwt");

    List<String> dangling = new ArrayList<>();
    JsonNode paths = document.path("paths");
    for (String path : paths.propertyNames()) {
      for (String verb : VERBS) {
        JsonNode operation = paths.path(path).path(verb);
        if (operation.isMissingNode()) {
          continue;
        }
        for (JsonNode requirement : operation.path("security")) {
          for (String scheme : requirement.propertyNames()) {
            if (!defined.contains(scheme)) {
              dangling.add(verb + " " + path + " -> " + scheme);
            }
          }
        }
      }
    }

    assertThat(dangling)
        .as(
            "An operation-level security requirement naming an undefined scheme silently removes"
                + " the global bearer requirement from that operation — the 'bearerAuth' vs"
                + " 'bearer-jwt' defect (REQ-SEC-052), which 108 operations carried into the"
                + " Android app's frozen contract without anything failing.")
        .isEmpty();
  }

  @Test
  @DisplayName("exactly the two anonymous reads declare an empty security list")
  void onlyTheTwoAnonymousReadsAreDocumentedAsPublic() {
    JsonNode document = openapi();
    Set<String> declaredPublic = new LinkedHashSet<>();
    JsonNode paths = document.path("paths");
    for (String path : paths.propertyNames()) {
      for (String verb : VERBS) {
        JsonNode operation = paths.path(path).path(verb);
        if (operation.isMissingNode()) {
          continue;
        }
        JsonNode security = operation.get("security");
        if (security != null && security.isArray() && security.isEmpty()) {
          declaredPublic.add(verb + " " + path);
        }
      }
    }

    assertThat(declaredPublic)
        .as(
            "REQ-SEC-052 names exactly two operations that answer without a token. An empty"
                + " security list on a third is a public endpoint nobody decided on; a missing one"
                + " on either of these two makes a generated client attach a bearer on the calls it"
                + " makes before it can have one.")
        .containsExactlyInAnyOrderElementsOf(ANONYMOUS_OPERATIONS);
  }
}
