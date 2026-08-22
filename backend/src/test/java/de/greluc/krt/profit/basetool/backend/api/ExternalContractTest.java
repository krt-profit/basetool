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

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards the <b>external contract set</b> (REQ-API-009, ADR-0136): the operations a shipped client
 * depends on, which may no longer change shape in place.
 *
 * <p>REQ-API-001's carve-out lets an endpoint consumed only by the in-repo frontend change its
 * response shape without an {@code /api/v2} bump, because frontend and backend deploy atomically. A
 * released Android build breaks that premise: it sits on devices for months, and a field the server
 * stops sending is a crash or a blank screen in a version nobody can redeploy. For the operations
 * listed here the carve-out therefore does not apply.
 *
 * <p><b>What this test can and cannot prove.</b> It reads the committed {@code openapi.json} — the
 * artifact REQ-API-007 already keeps in sync with the controllers — and fails when a contract
 * operation disappears, changes its verb, or loses a recorded response field. Those are the breaks
 * that silently reach a device. It does <em>not</em> compare types, nullability or enum values;
 * that needs a real schema diff against the previous release, which ADR-0136 records as the next
 * step rather than pretending this covers it.
 *
 * <p><b>Adding to the set is a deliberate act.</b> The list grows one app phase at a time, together
 * with the vhost allow-list that exposes those paths. Removing an entry is not a way to make this
 * test pass: it means retiring a contract, which is an {@code /api/v2} plus {@code @ApiDeprecation}
 * question and needs the sunset the shipped clients get to live through.
 */
class ExternalContractTest {

  /** The committed API document, the same artifact REQ-API-007 governs. */
  private static final String OPENAPI_RESOURCE = "/api/openapi.json";

  /**
   * One frozen operation: path, verb, and the response fields a shipped client may rely on.
   *
   * @param path the {@code /api/v1} path exactly as it appears in the document
   * @param method the HTTP verb, lower case, as OpenAPI spells it
   * @param responseFields response properties that must keep existing; additive change is fine
   */
  private record ContractOperation(String path, String method, Set<String> responseFields) {}

  /**
   * The contract set: the app's phase 1 (auth, terms gate, pending-approval screen, settings) plus
   * what each later phase adds as it is actually consumed — exactly the paths the API vhost
   * allow-lists.
   *
   * <p>Recorded from the generated document rather than hand-written, so the baseline is what the
   * server actually serves and not what someone believed it served.
   */
  private static final List<ContractOperation> CONTRACT =
      List.of(
          new ContractOperation(
              "/api/v1/terms/status", "get", Set.of("accepted", "currentVersion")),
          new ContractOperation(
              "/api/v1/terms/acceptance", "post", Set.of("accepted", "currentVersion")),
          new ContractOperation("/api/v1/me/active-org-unit", "get", Set.of("orgUnitId")),
          new ContractOperation(
              "/api/v1/me/capabilities",
              "get",
              Set.of("canSeeBlueprintOverview", "canViewJobOrders", "canViewOwnJobOrders")),
          new ContractOperation(
              "/api/v1/users/me/registration-status", "get", Set.of("approvalStatus")),
          // ADR-0138: the app renders the wording from here instead of shipping a copy in the APK,
          // so a field dropped from this response blanks a legal document on a build nobody can
          // redeploy. `sections` is the field that matters most -- rename it and the terms screen
          // shows a heading and nothing else.
          new ContractOperation(
              "/api/v1/terms/document",
              "get",
              Set.of("version", "title", "intro", "sections", "lastUpdated")),
          // Phase 2, first entry: the app's org-unit switcher. It exists as a me-scoped endpoint
          // rather than reusing GET /{id}/memberships precisely so that the public vhost never has
          // to allow-list a path able to name another user. `isProfitEligible` is deliberately NOT
          // frozen -- the app does not read it, and freezing a field nobody consumes buys the
          // backend a constraint for nothing. Adding it later is one more deliberate edit, which
          // is the process working rather than a gap.
          new ContractOperation(
              "/api/v1/users/me/memberships",
              "get",
              Set.of("orgUnitId", "orgUnitName", "orgUnitShorthand", "kind")),
          // Phase 2, the Einsatz list. `/search` rather than the plain `/missions`: the app's chip
          // row filters by text, status and date range, and the plain list takes only paging -- so
          // filtering would have to happen on a page the server had already truncated. Deliberately
          // the ONLY missions path in the set: the detail screen is not built yet, and opening a
          // family before a client consumes it is exactly what ADR-0135 tells the allow-list not to
          // do.
          //
          // Both levels are frozen. The envelope's `totalElements` is what the list states as its
          // count, and the row fields are what a member actually reads; `size`, `sort`,
          // `calendarLink` and `version` are left out because the app does not consume them, and
          // freezing a field nobody reads buys the backend a constraint for nothing.
          new ContractOperation(
              "/api/v1/missions/search",
              "get",
              Set.of(
                  "content",
                  "page",
                  "totalElements",
                  "totalPages",
                  "id",
                  "name",
                  "status",
                  "meetingTime",
                  "plannedStartTime",
                  "actualStartTime",
                  "plannedEndTime",
                  "isInternal",
                  "operation",
                  "owningSquadron",
                  "meetingPoint")),
          // Phase 2, the Einsatz detail. Anonymous by design like the search above it, and
          // redacted for an outsider by MissionGuestRedactor (ADR-0034): no description, no owner,
          // no managers, and each participant loses their payout preference and comment. An
          // internal or terminal Einsatz is refused outright with 403. What is frozen here is what
          // the app reads for its seven tabs -- the counters and the four planning collections
          // among them, since a tab whose collection vanished would render as an empty screen with
          // no error anywhere.
          new ContractOperation(
              "/api/v1/missions/{id}",
              "get",
              Set.of(
                  "id",
                  "name",
                  "description",
                  "status",
                  "meetingTime",
                  "plannedStartTime",
                  "actualStartTime",
                  "plannedEndTime",
                  "isInternal",
                  "meetingPoint",
                  "operation",
                  "owningSquadron",
                  "partyLeadUser",
                  "partyLeadGuestName",
                  "registeredParticipants",
                  "checkedInParticipants",
                  "participants",
                  "assignedUnits",
                  "steps",
                  "objectives",
                  "frequencies")),
          // The Finanzen tab. Unlike the two above it this one is NOT anonymous:
          // `isAuthenticated() and isMemberOrAbove() and canSeeMission(#missionId)`, so it answers
          // 401 without a token and 403 to a guest (REQ-SEC-037).
          new ContractOperation(
              "/api/v1/missions/{missionId}/finance-entries",
              "get",
              Set.of(
                  "content",
                  "page",
                  "totalElements",
                  "totalPages",
                  "id",
                  "type",
                  "amount",
                  "note")),
          new ContractOperation(
              "/api/v1/missions/{missionId}/finance-entries/summary",
              "get",
              Set.of("total", "incomeSum", "incomeCount", "expenseSum", "expenseCount")));

  @Test
  @DisplayName("every operation a shipped client depends on is still served, with the same verb")
  void theContractOperationsStillExist() throws IOException {
    JsonNode paths = openapi().get("paths");

    for (ContractOperation operation : CONTRACT) {
      assertThat(paths.has(operation.path()))
          .as(
              "%s is in the external contract set (REQ-API-009): a shipped app calls it and cannot"
                  + " be redeployed. Retiring it needs /api/v2 + @ApiDeprecation and a sunset, not"
                  + " a deletion",
              operation.path())
          .isTrue();
      assertThat(paths.get(operation.path()).has(operation.method()))
          .as(
              "%s no longer accepts %s — a verb change is a break for every client in the field",
              operation.path(), operation.method().toUpperCase(java.util.Locale.ROOT))
          .isTrue();
    }
  }

  @Test
  @DisplayName("no contract response has lost a field a shipped client may already read")
  void theContractResponsesKeepTheirFields() throws IOException {
    JsonNode document = openapi();

    for (ContractOperation operation : CONTRACT) {
      Set<String> present = responseProperties(document, operation);
      assertThat(present)
          .as(
              "%s %s dropped a response field. Additive change is fine and this assertion allows "
                  + "it; removal or rename is what an old build cannot survive (REQ-API-009)",
              operation.method().toUpperCase(java.util.Locale.ROOT), operation.path())
          .containsAll(operation.responseFields());
    }
  }

  @Test
  void theContractSetIsNotSilentlyEmptied() {
    // A guard on the guard: deleting entries is the easy way to make the two tests above pass, and
    // it is exactly the move REQ-API-009 forbids. The floor is the phase-1 set; it may only grow.
    assertThat(CONTRACT).hasSizeGreaterThanOrEqualTo(5);
  }

  /**
   * Collects the property names of an operation's 2xx response body schema.
   *
   * <p>Follows the schema's {@code $ref}, or — for a list endpoint — the {@code $ref} of its {@code
   * items}. Without the second case every array-returning operation resolves to nothing, and an
   * entry recording no fields would then pass this guard while proving nothing.
   *
   * <p>For a <strong>paged</strong> response it additionally descends into {@code content}'s item
   * schema, so the recorded set spans the envelope and the rows. Stopping at the envelope would
   * freeze {@code totalElements} and leave every field a member actually reads unguarded — dropping
   * {@code name} from the row would break the Einsatz list in the field while this guard stayed
   * green.
   *
   * @param document the parsed API document
   * @param operation the contract operation to resolve
   * @return the property names, or an empty set when the response carries no body schema
   */
  private static Set<String> responseProperties(JsonNode document, ContractOperation operation) {
    JsonNode responses =
        document.get("paths").get(operation.path()).get(operation.method()).get("responses");
    Set<String> properties = new TreeSet<>();
    for (Map.Entry<String, JsonNode> response : responses.properties()) {
      if (!response.getKey().startsWith("2")) {
        continue;
      }
      JsonNode content = response.getValue().get("content");
      if (content == null) {
        continue;
      }
      for (Map.Entry<String, JsonNode> mediaType : content.properties()) {
        JsonNode schemaNode = mediaType.getValue().path("schema");
        // A list endpoint's schema is `{"type": "array", "items": {"$ref": ...}}` — the fields a
        // client reads are the ITEM's. Following only the top-level $ref made this helper return
        // nothing for every array response, which for an operation with no recorded fields would
        // have passed vacuously: the guard would have looked green while seeing nothing at all.
        JsonNode ref = schemaNode.get("$ref");
        if (ref == null) {
          ref = schemaNode.path("items").get("$ref");
        }
        if (ref == null) {
          continue;
        }
        String schemaName = ref.asString().substring(ref.asString().lastIndexOf('/') + 1);
        JsonNode schema = document.get("components").get("schemas").get(schemaName);
        JsonNode schemaProperties = schema == null ? null : schema.get("properties");
        if (schemaProperties != null) {
          properties.addAll(schemaProperties.propertyNames());
          properties.addAll(pagedRowProperties(document, schemaProperties));
        }
      }
    }
    return properties;
  }

  /**
   * Collects the property names of a paged envelope's row schema.
   *
   * <p>A {@code PageResponse} carries the rows under {@code content}; those properties are the ones
   * a client reads item by item, and they are invisible to a resolver that stops at the envelope.
   *
   * @param document the parsed API document
   * @param envelopeProperties the properties of the already-resolved response schema
   * @return the row property names, or an empty set when the schema is not a paged envelope
   */
  private static Set<String> pagedRowProperties(JsonNode document, JsonNode envelopeProperties) {
    JsonNode itemRef = envelopeProperties.path("content").path("items").get("$ref");
    if (itemRef == null) {
      return Set.of();
    }
    String rowName = itemRef.asString().substring(itemRef.asString().lastIndexOf('/') + 1);
    JsonNode rowSchema = document.get("components").get("schemas").get(rowName);
    JsonNode rowProperties = rowSchema == null ? null : rowSchema.get("properties");
    return rowProperties == null ? Set.of() : new TreeSet<>(rowProperties.propertyNames());
  }

  /**
   * Reads the committed API document from the classpath.
   *
   * @return the parsed document
   * @throws IOException if the resource cannot be read
   */
  private static JsonNode openapi() throws IOException {
    try (InputStream in = ExternalContractTest.class.getResourceAsStream(OPENAPI_RESOURCE)) {
      assertThat(in).as("%s must be on the classpath", OPENAPI_RESOURCE).isNotNull();
      return new ObjectMapper().readTree(in);
    }
  }
}
