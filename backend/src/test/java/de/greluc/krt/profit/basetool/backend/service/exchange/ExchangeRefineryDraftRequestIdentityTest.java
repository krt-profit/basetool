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

package de.greluc.krt.profit.basetool.backend.service.exchange;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractDto;
import de.greluc.krt.profit.basetool.backend.model.dto.RefineryExtractOrderDto;
import de.greluc.krt.profit.basetool.backend.model.dto.exchange.ExchangeRefineryDraftRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Valid;
import jakarta.validation.Validator;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.io.IOException;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Proves that the exchange refinery draft's own request record reads exactly like the web import's
 * extract it replaced on the frozen route (REQ-XCH-038): the same components, types and
 * constraints, the same JSON after a round trip of every published fixture and of every one-field
 * mutation of them, the same validation outcome, the same extract handed to the import, and the
 * same schema in the committed OpenAPI document.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = "app.security.ingest-gateway.client-ids=test-ingest-gateway")
class ExchangeRefineryDraftRequestIdentityTest {

  private static final Path FIXTURES = Path.of("../docs/exchange/examples/v1/refinery-draft");

  private static final Path OPENAPI = Path.of("src/main/resources/api/openapi.json");

  private static final String FULL_EXTRACT =
      """
      {"schemaVersion":1,"tool":"basetool-sc-extractor","toolVersion":"1.4.0",
       "model":"qwen3-vl:8b-instruct","generatedAt":"2026-06-05T20:00:00Z","clientLanguage":"en",
       "orders":[{"panelType":"SETUP","quoted":true,"layoutConfidence":0.92,
         "rawLocationName":"LEVSKI","rawMethodName":"FERRON EXCHANGE","rawInManifestTotal":32295,
         "rawToRefineTotal":32295,"expenses":48928.00,"durationMinutes":1258,"totalYieldScu":12.5,
         "sourceImages":[{"name":"frame_213823.png","width":3840,"height":2160,"cropMode":"vlm",
           "capturedAt":"2026-06-05T19:38:23Z"}],
         "goods":[{"rowIndex":0,"rawMaterialName":"LINDINIUM (ORE)","quality":618,
           "inputQuantity":957,"outputQuantity":448,"refine":true,"confidence":0.95,
           "sourceImage":"frame_213823.png"}]}]}
      """;

  @Autowired private JsonMapper mapper;

  @Autowired private Validator validator;

  @Test
  void bothRecordsDeclareTheSameComponentsTypesAndConstraints() {
    assertSameShape(RefineryExtractDto.class, ExchangeRefineryDraftRequest.class);
  }

  @Test
  void aDriftedConstraintIsCaught() {
    assertThatThrownBy(() -> assertSameShape(Drifted.class, ExchangeRefineryDraftRequest.class))
        .isInstanceOf(AssertionError.class);
  }

  @Test
  void everyFixtureAndEveryOneFieldMutationReadsTheSameThroughBothTypes() throws IOException {
    List<JsonNode> documents = new ArrayList<>();
    for (Path fixture : fixtures()) {
      documents.add(mapper.readTree(Files.readString(fixture, StandardCharsets.UTF_8)));
    }
    documents.add(mapper.readTree(FULL_EXTRACT));
    List<JsonNode> cases = new ArrayList<>();
    for (JsonNode document : documents) {
      cases.add(document);
      cases.addAll(mutations(document));
    }
    int compared = 0;
    for (JsonNode json : cases) {
      assertReadsTheSame(json);
      compared++;
    }
    assertThat(compared).as("fixtures and their mutations").isGreaterThan(150);
  }

  @Test
  void theCommittedOpenApiDocumentDescribesBothRequestsAlike() throws IOException {
    JsonNode document = mapper.readTree(Files.readString(OPENAPI, StandardCharsets.UTF_8));
    JsonNode exchange =
        document.at("/paths/~1api~1v1~1exchange~1me~1drafts~1refinery-orders/post/requestBody");
    JsonNode web =
        document.at("/paths/~1api~1v1~1refinery-orders~1import-extract/post/requestBody");
    assertThat(exchange.at("/content/application~1json/schema/$ref").asString())
        .isEqualTo("#/components/schemas/ExchangeRefineryDraftRequest");
    assertThat(web.at("/content/application~1json/schema/$ref").asString())
        .isEqualTo("#/components/schemas/RefineryExtractDto");
    assertThat(inline(document, exchange)).isEqualTo(inline(document, web));
  }

  /**
   * Asserts that a document deserializes alike through both types: both fail, or both succeed with
   * the same JSON, the same violations and the same extract for the import.
   *
   * @param json the document
   */
  private void assertReadsTheSame(@NotNull JsonNode json) {
    RefineryExtractDto web = read(json, RefineryExtractDto.class);
    ExchangeRefineryDraftRequest exchange = read(json, ExchangeRefineryDraftRequest.class);
    assertThat(exchange == null).as("readable alike: %s", json).isEqualTo(web == null);
    if (web == null || exchange == null) {
      return;
    }
    JsonNode exchangeJson = mapper.valueToTree(exchange);
    JsonNode webJson = mapper.valueToTree(web);
    assertThat(exchangeJson).as(json.toString()).isEqualTo(webJson);
    assertThat(violations(validator.validate(exchange)))
        .as(json.toString())
        .isEqualTo(violations(validator.validate(web)));
    assertThat(ExchangeDraftService.extract(exchange)).as(json.toString()).isEqualTo(web);
  }

  /**
   * Reads a document as one type.
   *
   * @param json the document
   * @param type the target type
   * @param <T> the target type
   * @return the value, or {@code null} when the document does not bind
   */
  private <T> @Nullable T read(@NotNull JsonNode json, @NotNull Class<T> type) {
    try {
      return mapper.treeToValue(json, type);
    } catch (JacksonException unreadable) {
      return null;
    }
  }

  /**
   * Renders violations by path and constraint.
   *
   * @param violations the violations
   * @param <T> the validated type
   * @return {@code path:Constraint}, sorted
   */
  private static <T> @NotNull Set<String> violations(
      @NotNull Set<ConstraintViolation<T>> violations) {
    return violations.stream()
        .map(
            v ->
                v.getPropertyPath()
                    + ":"
                    + v.getConstraintDescriptor().getAnnotation().annotationType().getSimpleName())
        .collect(Collectors.toCollection(TreeSet::new));
  }

  /**
   * Builds every one-field mutation of a document: each member removed, set to {@code null}, and
   * replaced by an out-of-range number, an over-long string and a value of the wrong type.
   *
   * @param document the document
   * @return the mutations
   */
  private static @NotNull List<JsonNode> mutations(@NotNull JsonNode document) {
    List<JsonNode> out = new ArrayList<>();
    for (Member member : members(document, "")) {
      for (int variant = 0; variant < 6; variant++) {
        JsonNode copy = document.deepCopy();
        JsonNode holder = member.parent().isEmpty() ? copy : copy.at(member.parent());
        if (holder instanceof ObjectNode object) {
          String name = member.name();
          switch (variant) {
            case 0 -> object.remove(name);
            case 1 -> object.putNull(name);
            case 2 -> object.put(name, -1);
            case 3 -> object.put(name, 2_000_000_000.5);
            case 4 -> object.put(name, "x".repeat(300));
            default -> object.putArray(name);
          }
        } else if (holder instanceof ArrayNode array) {
          int index = member.index();
          switch (variant) {
            case 0 -> array.remove(index);
            case 1 -> array.set(index, array.nullNode());
            default -> array.set(index, array.stringNode("x"));
          }
        }
        out.add(copy);
      }
    }
    return out;
  }

  /**
   * Lists every member and element of a document with its parent's JSON Pointer, depth first.
   *
   * @param node the node
   * @param at the node's own pointer
   * @return the members and elements below it
   */
  private static @NotNull List<Member> members(@NotNull JsonNode node, @NotNull String at) {
    List<Member> out = new ArrayList<>();
    if (node.isObject()) {
      for (String name : node.propertyNames()) {
        out.add(new Member(at, name, -1));
        out.addAll(members(node.get(name), at + "/" + name));
      }
    } else if (node.isArray()) {
      for (int i = 0; i < node.size(); i++) {
        out.add(new Member(at, String.valueOf(i), i));
        out.addAll(members(node.get(i), at + "/" + i));
      }
    }
    return out;
  }

  /**
   * One member of an object or element of an array.
   *
   * @param parent the JSON Pointer of the containing node, empty for the document root
   * @param name the member name, or the element index as text
   * @param index the element index, {@code -1} for an object member
   */
  private record Member(@NotNull String parent, @NotNull String name, int index) {}

  /**
   * Lists the published refinery draft fixtures.
   *
   * @return every fixture, valid and invalid
   * @throws IOException if a folder cannot be listed
   */
  private static @NotNull List<Path> fixtures() throws IOException {
    List<Path> out = new ArrayList<>();
    for (String folder : List.of("valid", "invalid")) {
      try (Stream<Path> files = Files.list(FIXTURES.resolve(folder))) {
        out.addAll(files.filter(p -> p.toString().endsWith(".json")).sorted().toList());
      }
    }
    assertThat(out).as("refinery draft fixtures").hasSizeGreaterThanOrEqualTo(2);
    return out;
  }

  /**
   * Replaces every component reference of an OpenAPI node by the schema it names.
   *
   * @param document the OpenAPI document
   * @param node the node
   * @return the node with every reference inlined
   */
  private static @NotNull JsonNode inline(@NotNull JsonNode document, @NotNull JsonNode node) {
    if (node.isObject()) {
      JsonNode ref = node.get("$ref");
      if (ref != null && ref.isString() && ref.asString().startsWith("#/")) {
        return inline(document, document.at(ref.asString().substring(1)));
      }
      ObjectNode copy = ((ObjectNode) node).objectNode();
      node.propertyNames().forEach(name -> copy.set(name, inline(document, node.get(name))));
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = ((ArrayNode) node).arrayNode();
      node.forEach(element -> copy.add(inline(document, element)));
      return copy;
    }
    return node;
  }

  /**
   * Asserts that two records declare the same components with the same types and annotations,
   * nested records compared alike.
   *
   * @param expected the reference record
   * @param actual the record that must match it
   */
  private static void assertSameShape(@NotNull Class<?> expected, @NotNull Class<?> actual) {
    RecordComponent[] left = expected.getRecordComponents();
    RecordComponent[] right = actual.getRecordComponents();
    assertThat(names(right)).as(actual.getSimpleName()).isEqualTo(names(left));
    for (int i = 0; i < left.length; i++) {
      String where = actual.getSimpleName() + "." + right[i].getName();
      assertThat(describe(right[i].getAnnotatedType()))
          .as(where)
          .isEqualTo(describe(left[i].getAnnotatedType()));
      Class<?> leftNested = nestedRecord(left[i].getAnnotatedType());
      Class<?> rightNested = nestedRecord(right[i].getAnnotatedType());
      assertThat(rightNested == null).as(where).isEqualTo(leftNested == null);
      if (leftNested != null && rightNested != null) {
        assertSameShape(leftNested, rightNested);
      }
    }
  }

  /**
   * Lists a record's component names in order.
   *
   * @param components the components
   * @return the names
   */
  private static @NotNull List<String> names(@NotNull RecordComponent[] components) {
    return Arrays.stream(components).map(RecordComponent::getName).toList();
  }

  /**
   * Describes an annotated type with its annotations and those of its type arguments, every record
   * type named {@code RECORD}.
   *
   * @param type the annotated type
   * @return the description
   */
  private static @NotNull String describe(@NotNull AnnotatedType type) {
    StringBuilder out = new StringBuilder();
    Arrays.stream(type.getAnnotations())
        .map(Object::toString)
        .sorted()
        .forEach(a -> out.append(a).append(' '));
    Class<?> raw =
        type.getType() instanceof Class<?> c
            ? c
            : (Class<?>) ((ParameterizedType) type.getType()).getRawType();
    out.append(raw.isRecord() ? "RECORD" : raw.getName());
    if (type instanceof AnnotatedParameterizedType parameterized) {
      out.append('<');
      for (AnnotatedType argument : parameterized.getAnnotatedActualTypeArguments()) {
        out.append(describe(argument));
      }
      out.append('>');
    }
    return out.toString();
  }

  /**
   * Finds the record a component holds, directly or as a list element.
   *
   * @param type the component's annotated type
   * @return the record type, or {@code null} when it holds none
   */
  private static @Nullable Class<?> nestedRecord(@NotNull AnnotatedType type) {
    if (type.getType() instanceof Class<?> c) {
      return c.isRecord() ? c : null;
    }
    if (type instanceof AnnotatedParameterizedType parameterized) {
      for (AnnotatedType argument : parameterized.getAnnotatedActualTypeArguments()) {
        Class<?> nested = nestedRecord(argument);
        if (nested != null) {
          return nested;
        }
      }
    }
    return null;
  }

  /**
   * The extract with one constraint narrowed, the planted drift the shape check must catch.
   *
   * @param schemaVersion the version
   * @param tool the producer, narrowed to 50 characters
   * @param toolVersion the producer version
   * @param model the model
   * @param generatedAt the production time
   * @param clientLanguage the client language
   * @param orders the orders
   */
  private record Drifted(
      @jakarta.validation.constraints.NotNull Integer schemaVersion,
      @Size(max = 50) String tool,
      @Size(max = 50) String toolVersion,
      @Size(max = 100) String model,
      Instant generatedAt,
      @Size(max = 16) String clientLanguage,
      @NotEmpty @Size(max = 5)
          List<@jakarta.validation.constraints.NotNull @Valid RefineryExtractOrderDto> orders) {}
}
