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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards the external contract set (REQ-API-009, ADR-0136): operations a shipped client depends on,
 * which may not change shape in place.
 *
 * <p>Reads the committed {@code openapi.json} and fails when a contract operation disappears,
 * changes its verb, loses a recorded response field, gains a required request field, changes a
 * frozen enum, or changes a recorded query parameter. Removing an entry means retiring a contract
 * via {@code /api/v2}.
 */
class ExternalContractTest {

  /** The committed API document, the same artifact REQ-API-007 governs. */
  private static final String OPENAPI_RESOURCE = "/api/openapi.json";

  /**
   * How far into a response's schema graph the field guard looks.
   *
   * <p>Two: a paged response spends the first level on its own rows, so a row's nested object — a
   * ship's {@code shipType}, whose {@code name} is the whole point of the card — needs the second.
   */
  private static final int MAX_NESTING = 2;

  /**
   * One frozen operation: path, verb, response fields a shipped client relies on, query parameters
   * it sends, and for a write the request fields the server may require.
   *
   * @param path the {@code /api/v1} path exactly as it appears in the document
   * @param method the HTTP verb, lower case
   * @param responseFields response properties that must keep existing; additions are fine
   * @param requiredRequestFields the request body's {@code required} list, frozen exactly; empty
   *     without a body or when nothing is required
   * @param queryParams query parameters the app sends, as {@code name:type}, frozen as a subset;
   *     empty for an operation addressed by path alone
   */
  private record ContractOperation(
      String path,
      String method,
      Set<String> responseFields,
      Set<String> requiredRequestFields,
      Set<String> queryParams) {

    /**
     * A read, or a write whose request body carries no required field.
     *
     * @param path the {@code /api/v1} path
     * @param method the HTTP verb, lower case
     * @param responseFields the frozen response properties
     */
    ContractOperation(String path, String method, Set<String> responseFields) {
      this(path, method, responseFields, Set.of(), Set.of());
    }

    /**
     * Creates a write operation whose request body has a {@code required} list to freeze.
     *
     * @param path the {@code /api/v1} path
     * @param method the HTTP verb, lower case
     * @param responseFields the frozen response properties
     * @param requiredRequestFields the request body's frozen {@code required} list
     */
    ContractOperation(
        String path, String method, Set<String> responseFields, Set<String> requiredRequestFields) {
      this(path, method, responseFields, requiredRequestFields, Set.of());
    }

    /**
     * Records the query parameters a shipped client addresses this operation by.
     *
     * @param frozen the parameters as {@code name:type} using the document's schema type; an
     *     array's element type is omitted
     * @return a copy of this operation carrying the frozen parameters
     */
    ContractOperation addressedBy(Set<String> frozen) {
      return new ContractOperation(path, method, responseFields, requiredRequestFields, frozen);
    }
  }

  /**
   * The contract set: every operation the Android app consumes, matching the paths the API vhost
   * allow-lists, recorded from the generated document.
   */
  /**
   * The nginx include that decides what the internet can reach through the API vhost (ADR-0162).
   */
  private static final String ALLOW_LIST = "docker/edge/include/api-allowlist.conf";

  /** {@code if ($uri = "/api/v1/…") { set $krt_api_allowed 1; }} */
  private static final Pattern EXACT_RULE = Pattern.compile("\\$uri\\s*=\\s*\"([^\"]+)\"");

  /** {@code if ($uri ~ "^/api/v1/…$") { set $krt_api_allowed 1; }} */
  private static final Pattern REGEX_RULE = Pattern.compile("\\$uri\\s*~\\s*\"([^\"]+)\"");

  /** A {@code {name}} segment in a contract path. */
  private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-zA-Z]+}");

  /** Any uuid; the rules match the shape, not the value. */
  private static final String SAMPLE_UUID = "00000000-0000-4000-8000-000000000000";

  /**
   * Placeholders that are not uuids, and what they stand for.
   *
   * <p>Only one exists today: the bank account's all-members visibility toggle, whose rule matches
   * {@code (true|false)} rather than a uuid class. A new entry here is cheaper than a test that
   * quietly substitutes the wrong shape and reports a gap that is not there.
   */
  private static final java.util.Map<String, String> PLACEHOLDERS =
      java.util.Map.of(
          "{enabled}",
          "true",
          "{roleCode}",
          "KOMMANDOLEITER",
          "{key}",
          "job_order.age_yellow_days");

  /**
   * Response fields promised by all seven operations that answer with an org-unit bank account's
   * settings, since the app maps them through one shared mapper. Includes the nested approval-limit
   * names {@code BankApprovalLimitsDto.toModel} reads.
   */
  private static final Set<String> BANK_ACCOUNT_SETTINGS =
      Set.of(
          "accountId",
          "accountName",
          "balanceTarget",
          "version",
          "canSetTarget",
          "canConfigureVisibility",
          "visibilityConfigurable",
          "allMembersSupported",
          "availableRoleCodes",
          "grantedRoleCodes",
          "allMembersGranted",
          "approvalLimits",
          "canConfigureApprovalLimits",
          "configurable",
          "areaMembersSupported",
          "allMembersLimit",
          "areaMembersLimit",
          "roleLimits",
          "userLimits",
          "userId",
          "displayName",
          "limitAmount");

  /**
   * Response fields promised by every operation returning a {@code JobOrderDto}, since the app maps
   * them through one shared mapper. {@code version} is required for assignee note edits.
   */
  private static final Set<String> JOB_ORDER_DETAIL =
      Set.of(
          "id",
          "displayId",
          "status",
          "priority",
          "type",
          "comment",
          "createdAt",
          "materials",
          "aggregatedMaterials",
          "assignees",
          "user",
          "effectiveName",
          "note",
          "version",
          "handovers",
          "redacted",
          "canEdit",
          "requestingOrgUnit",
          "responsibleOrgUnit");

  /**
   * Response fields promised by the three allocation operations returning an {@code
   * InventoryItemDto}, since the app maps them through one shared mapper. {@code jobOrderRest} and
   * {@code missionRest} bound new earmarks.
   */
  private static final Set<String> INVENTORY_ROW =
      Set.of(
          "id",
          "material",
          "name",
          "quantityType",
          "location",
          "user",
          "effectiveName",
          "amount",
          "quality",
          "personal",
          "owningSquadron",
          "canEdit",
          "note",
          "version",
          "jobOrderAllocations",
          "jobOrderId",
          "jobOrderDisplayId",
          "jobOrderRest",
          "missionAllocations",
          "missionId",
          "missionName",
          "missionPlannedStartTime",
          "missionRest");

  /**
   * Response fields promised by every operation returning a {@code MissionDto}, since the app maps
   * them through one shared mapper. {@code user} identifies the caller's own participant row.
   */
  /**
   * What a step row promises, for all five writes that answer with the Ablauf.
   *
   * <p>{@code meta} is the line under the title and {@code done} is the tick; a row without an
   * {@code id} is dropped, because the id is what the next write addresses.
   */
  private static final Set<String> STEP_ROW = Set.of("id", "title", "meta", "done");

  /**
   * What an objective row promises, for all four writes that answer with the Ziele.
   *
   * <p>{@code kind} is what separates a Primärziel from a Nicht-Ziel, which is the whole structure
   * of that section rather than a label on it.
   */
  private static final Set<String> OBJECTIVE_ROW = Set.of("id", "title", "kind");

  private static final Set<String> MISSION_DETAIL =
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
          "user",
          "startTime",
          "payoutPreference",
          "assignedUnits",
          "steps",
          "objectives",
          "frequencies");

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
              Set.of(
                  "canSeeBlueprintOverview",
                  "canViewJobOrders",
                  "canViewOwnJobOrders",
                  "canViewBankStaff",
                  "canManageBank")),
          new ContractOperation(
              "/api/v1/users/me/registration-status", "get", Set.of("approvalStatus")),
          new ContractOperation(
              "/api/v1/terms/document",
              "get",
              Set.of("version", "title", "intro", "sections", "lastUpdated")),
          new ContractOperation(
              "/api/v1/users/me/memberships",
              "get",
              Set.of("orgUnitId", "orgUnitName", "orgUnitShorthand", "kind")),
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
                      "meetingPoint"))
              .addressedBy(
                  Set.of(
                      "query:string",
                      "status:array",
                      "start:string",
                      "end:string",
                      "page:integer",
                      "size:integer",
                      "sort:string")),
          new ContractOperation("/api/v1/missions/{id}", "get", MISSION_DETAIL),
          new ContractOperation(
              "/api/v1/missions/{id}/join",
              "post",
              Set.of("id", "participants", "user", "registeredParticipants")),
          new ContractOperation(
              "/api/v1/missions/{id}/participants/{participantId}/slim", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/missions/{id}/participants/{participantId}/check-in/slim",
              "post",
              Set.of("id", "user", "startTime")),
          new ContractOperation(
              "/api/v1/missions/{id}/participants/{participantId}/check-out/slim",
              "post",
              Set.of("id", "user", "endTime")),
          new ContractOperation(
              "/api/v1/missions/{id}/participants/{participantId}/payout-preference/slim",
              "put",
              Set.of("id", "payoutPreference"),
              Set.of("preference")),
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
                      "note"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/missions/{missionId}/finance-entries/summary",
              "get",
              Set.of("total", "incomeSum", "incomeCount", "expenseSum", "expenseCount")),
          new ContractOperation(
              "/api/v1/finance-entries",
              "post",
              Set.of("id", "missionId", "participant", "type", "amount", "note", "version"),
              Set.of("amount", "missionId", "participantId", "type")),
          new ContractOperation(
              "/api/v1/finance-entries/{entryId}",
              "put",
              Set.of("id", "missionId", "participant", "type", "amount", "note", "version"),
              Set.of("amount", "type", "version")),
          new ContractOperation("/api/v1/finance-entries/{entryId}", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/operations/{id}/payouts/paid-out",
              "put",
              Set.of("participantKey", "paidOut", "paidOutAt", "paidOutByName"),
              Set.of("participantKey")),
          new ContractOperation(
                  "/api/v1/inventory/aggregated",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "material",
                      "amount",
                      "quality",
                      "maxQuality",
                      "name",
                      "quantityType"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
                  "/api/v1/inventory/all/grouped",
                  "get",
                  Set.of(
                      "material",
                      "totalAmount",
                      "averageQuality",
                      "maxQuality",
                      "stacks",
                      "user",
                      "location",
                      "personal",
                      "entryCount"))
              .addressedBy(Set.of("materialIds:array")),
          new ContractOperation(
                  "/api/v1/orders",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "displayId",
                      "status",
                      "priority",
                      "type",
                      "createdAt",
                      "materials",
                      "redacted"))
              .addressedBy(Set.of("status:array", "page:integer", "size:integer")),
          new ContractOperation("/api/v1/orders/{id}", "get", JOB_ORDER_DETAIL),
          new ContractOperation(
              "/api/v1/orders/{id}/assignees/{userId}",
              "post",
              Set.of("id", "assignees", "user", "effectiveName", "note", "version")),
          new ContractOperation(
              "/api/v1/orders/{id}/assignees/{userId}",
              "delete",
              Set.of("id", "assignees", "user", "effectiveName", "note", "version")),
          new ContractOperation(
              "/api/v1/orders/{id}/assignees/{userId}/note",
              "put",
              Set.of("id", "assignees", "user", "effectiveName", "note", "version"),
              Set.of()),
          new ContractOperation(
                  "/api/v1/orders/{id}/assignees/{userId}/note",
                  "delete",
                  Set.of("id", "assignees", "user", "effectiveName", "note", "version"))
              .addressedBy(Set.of("version:integer")),
          new ContractOperation(
              "/api/v1/orders/{id}/status",
              "put",
              Set.of("id", "status", "version"),
              Set.of("status", "version")),
          new ContractOperation(
              "/api/v1/org-units/bank/balances",
              "get",
              Set.of(
                  "accountId",
                  "accountNo",
                  "accountName",
                  "balance",
                  "delta30d",
                  "sparkline",
                  "orgUnitName",
                  "canRequest",
                  "approvalLimit",
                  "approvalExempt")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}",
              "get",
              Set.of(
                  "detail", "account", "delta30d", "bookingCount", "name", "accountNo", "balance")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/settings", "get", BANK_ACCOUNT_SETTINGS),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/balance-target",
              "put",
              BANK_ACCOUNT_SETTINGS,
              Set.of("version")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/visibility/role/{roleCode}",
              "post",
              BANK_ACCOUNT_SETTINGS),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/visibility/role/{roleCode}",
              "delete",
              BANK_ACCOUNT_SETTINGS),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/visibility/all-members/{enabled}",
              "put",
              BANK_ACCOUNT_SETTINGS),
          new ContractOperation(
                  "/api/v1/org-units/bank/accounts/{id}/transactions",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "postingId",
                      "type",
                      "amount",
                      "note",
                      "createdAt",
                      "holderHandle",
                      "transactionId",
                      "reversedTransactionId",
                      "transferFee",
                      "counterpartyHandle"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/org-units/bank/requests",
              "get",
              Set.of(
                  "id",
                  "accountId",
                  "accountName",
                  "targetAccountId",
                  "type",
                  "amount",
                  "note",
                  "justification",
                  "status",
                  "requesterHandle",
                  "rejectReason",
                  "applicableLimit",
                  "requiresOwnerApproval",
                  "requiredApprover",
                  "ownerApprovalGranted",
                  "ownerApprovalGrantedByHandle",
                  "createdAt",
                  "version")),
          new ContractOperation(
              "/api/v1/org-units/bank/requests/foreign",
              "get",
              Set.of(
                  "id",
                  "accountId",
                  "accountName",
                  "type",
                  "amount",
                  "note",
                  "justification",
                  "status",
                  "requesterHandle",
                  "requiresOwnerApproval",
                  "requiredApprover",
                  "ownerApprovalGranted",
                  "ownerApprovalGrantedByHandle",
                  "createdAt",
                  "version")),
          new ContractOperation(
              "/api/v1/org-units/bank/transfer-targets", "get", Set.of("id", "name", "accountNo")),
          new ContractOperation(
              "/api/v1/org-units/bank/requests",
              "post",
              Set.of("id", "status", "requiresOwnerApproval", "requiredApprover", "version"),
              Set.of("sourceAccountId", "type", "amount")),
          new ContractOperation(
              "/api/v1/org-units/bank/requests/{id}",
              "put",
              Set.of("id", "amount", "note", "targetAccountId", "version"),
              Set.of("amount")),
          new ContractOperation(
              "/api/v1/org-units/bank/requests/{id}/cancel",
              "post",
              Set.of("id", "status", "version"),
              Set.of("version")),
          new ContractOperation(
              "/api/v1/org-units/bank/requests/{id}/owner-approval",
              "post",
              Set.of("id", "ownerApprovalGranted", "ownerApprovalGrantedByHandle", "version")),
          new ContractOperation(
              "/api/v1/org-units/bank/requests/{id}/owner-approval",
              "delete",
              Set.of("id", "ownerApprovalGranted", "version")),
          new ContractOperation(
                  "/api/v1/hangar/my-ships",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "name",
                      "shipType",
                      "insurance",
                      "location",
                      "fitted",
                      "manufacturer",
                      "version"))
              .addressedBy(Set.of("search:string", "page:integer", "size:integer")),
          new ContractOperation(
                  "/api/v1/hangar/squadron-overview",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "shipType",
                      "count",
                      "fittedCount"))
              .addressedBy(Set.of("search:string", "page:integer", "size:integer")),
          new ContractOperation("/api/v1/announcement", "get", Set.of("content", "updatedAt")),
          new ContractOperation(
                  "/api/v1/notifications",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "type",
                      "params",
                      "entityType",
                      "entityId",
                      "read",
                      "createdAt"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation("/api/v1/notifications/unread-count", "get", Set.of("count")),
          new ContractOperation("/api/v1/notifications/stream", "get", Set.of()),
          new ContractOperation("/api/v1/notifications/{id}/read", "post", Set.of("id", "read")),
          new ContractOperation(
              "/api/v1/notifications/read-all", "post", Set.of("affected", "unreadCount")),
          new ContractOperation("/api/v1/notifications/{id}", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/notifications/read", "delete", Set.of("affected", "unreadCount")),
          new ContractOperation(
              "/api/v1/users/me", "get", Set.of("id", "isLogistician", "isMissionManager")),
          new ContractOperation(
                  "/api/v1/operations/search",
                  "get",
                  Set.of("content", "page", "totalElements", "totalPages", "id", "name", "status"))
              .addressedBy(
                  Set.of(
                      "query:string",
                      "status:array",
                      "start:string",
                      "end:string",
                      "page:integer",
                      "size:integer",
                      "sort:string")),
          new ContractOperation(
              "/api/v1/operations/{id}",
              "get",
              Set.of("id", "name", "description", "status", "payoutPreliminary")),
          new ContractOperation(
              "/api/v1/operations/{id}/finance-summary",
              "get",
              Set.of(
                  "operationId", "totalSum", "missions", "truncated", "missionId", "missionName")),
          new ContractOperation(
              "/api/v1/operations/{id}/payouts",
              "get",
              Set.of(
                  "totalDonations",
                  "payouts",
                  "participantId",
                  "participantName",
                  "payoutPreference",
                  "shareAmount",
                  "donatedAmount",
                  "payoutAmount",
                  "paidOut")),
          new ContractOperation(
                  "/api/v1/personal-inventory",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "name",
                      "note",
                      "locationUexId",
                      "locationType",
                      "locationName",
                      "quantity",
                      "version"))
              .addressedBy(Set.of("q:string", "page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/personal-inventory",
              "post",
              Set.of("id", "name", "quantity", "locationUexId", "locationType", "version"),
              Set.of("name", "quantity", "locationUexId", "locationType")),
          new ContractOperation(
              "/api/v1/personal-inventory/{id}",
              "get",
              Set.of(
                  "id",
                  "name",
                  "note",
                  "locationUexId",
                  "locationType",
                  "locationName",
                  "quantity",
                  "version")),
          new ContractOperation(
              "/api/v1/personal-inventory/{id}",
              "put",
              Set.of("id", "name", "quantity", "locationUexId", "locationType", "version"),
              Set.of("name", "quantity", "locationUexId", "locationType", "version")),
          new ContractOperation("/api/v1/personal-inventory/{id}", "delete", Set.of()),
          new ContractOperation(
                  "/api/v1/uex/locations/search",
                  "get",
                  Set.of("uexId", "type", "name", "starSystemName", "parentName"))
              .addressedBy(Set.of("q:string", "limit:integer")),
          new ContractOperation(
                  "/api/v1/personal-blueprints",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "productKey",
                      "productName",
                      "acquiredAt",
                      "note",
                      "removable",
                      "version"))
              .addressedBy(Set.of("q:string", "page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/personal-blueprints",
              "post",
              Set.of("id", "productKey", "productName", "version"),
              Set.of("productKey")),
          new ContractOperation(
              "/api/v1/personal-blueprints/{id}",
              "put",
              Set.of("id", "productKey", "productName", "note", "acquiredAt", "version"),
              Set.of("version")),
          new ContractOperation("/api/v1/personal-blueprints/{id}", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/personal-blueprints/{id}/recipe",
              "get",
              Set.of(
                  "productName",
                  "variantCount",
                  "requirementGroups",
                  "ingredients",
                  "kind",
                  "name",
                  "quantityScu",
                  "quantityUnits",
                  "minQuality",
                  "quantityType")),
          new ContractOperation(
                  "/api/v1/personal-blueprints/craftability",
                  "get",
                  Set.of(
                      "blueprintId",
                      "recipeResolved",
                      "craftable",
                      "craftableWithRefinery",
                      "limitingMaterialName",
                      "limitingMaterialNameWithRefinery",
                      "materials",
                      "materialName",
                      "requiredScu",
                      "availableScu",
                      "missingScu",
                      "quantityType"))
              .addressedBy(Set.of("includeRefinery:boolean")),
          new ContractOperation(
                  "/api/v1/blueprints/products/search",
                  "get",
                  Set.of("productKey", "name", "manufacturerName", "ownedByCurrentUser"))
              .addressedBy(Set.of("q:string", "limit:integer")),
          new ContractOperation(
              "/api/v1/hangar/ships",
              "post",
              Set.of("id", "name", "shipType", "insurance", "location", "fitted", "version"),
              Set.of("insurance", "shipTypeId")),
          new ContractOperation(
              "/api/v1/hangar/ships/{id}",
              "put",
              Set.of("id", "name", "shipType", "insurance", "location", "fitted", "version"),
              Set.of("insurance", "shipTypeId")),
          new ContractOperation("/api/v1/hangar/ships/{id}", "delete", Set.of()),
          new ContractOperation(
                  "/api/v1/ship-types",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "name",
                      "manufacturer"))
              .addressedBy(Set.of("page:integer", "size:integer", "sort:string")),
          new ContractOperation("/api/v1/locations/home-locations", "get", Set.of("id", "name")),
          new ContractOperation(
                  "/api/v1/bank/accounts",
                  "get",
                  Set.of(
                      "content",
                      "id",
                      "accountNo",
                      "name",
                      "type",
                      "status",
                      "balance",
                      "orgUnit",
                      "version"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/bank/dashboard",
              "get",
              Set.of(
                  "management",
                  "accounts",
                  "totals",
                  "totalBalance",
                  "activeAccounts",
                  "closedAccounts",
                  "id",
                  "accountNo",
                  "name",
                  "type",
                  "status",
                  "balance",
                  "delta30d",
                  "sparkline")),
          new ContractOperation(
              "/api/v1/bank/holders",
              "get",
              Set.of("id", "handle", "active", "totalHeld", "version")),
          new ContractOperation(
              "/api/v1/bank/holders/{id}",
              "get",
              Set.of("id", "handle", "active", "totalHeld", "version")),
          new ContractOperation(
                  "/api/v1/orders/item-catalog", "get", Set.of("content", "id", "name"))
              .addressedBy(Set.of("search:string", "page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/orders/item-catalog/{gameItemId}/blueprints",
              "get",
              Set.of("id", "outputName", "scwikiKey")),
          new ContractOperation(
                  "/api/v1/users/search-bank",
                  "get",
                  Set.of(
                      "content", "totalElements", "id", "effectiveName", "displayName", "username"))
              .addressedBy(Set.of("query:string", "page:integer", "size:integer")),
          new ContractOperation("/api/v1/locations/refineries", "get", Set.of("id", "name")),
          new ContractOperation(
              "/api/v1/refining-methods",
              "get",
              Set.of("content", "id", "name", "ratingYield", "ratingCost", "ratingSpeed")),
          new ContractOperation(
              "/api/v1/me/org-units",
              "get",
              Set.of("orgUnitId", "orgUnitName", "orgUnitShorthand", "isProfitEligible", "kind")),
          new ContractOperation(
              "/api/v1/org-units/active-all-kinds",
              "get",
              Set.of("orgUnitId", "orgUnitName", "orgUnitShorthand", "isProfitEligible", "kind")),
          new ContractOperation(
                  "/api/v1/users/{id}/memberships",
                  "get",
                  Set.of(
                      "orgUnitId", "orgUnitName", "orgUnitShorthand", "isProfitEligible", "kind"))
              .addressedBy(Set.of("allKinds:boolean")),
          new ContractOperation(
                  "/api/v1/inventory/material/{materialId}",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "material",
                      "location",
                      "amount",
                      "quality",
                      "personal",
                      "note",
                      "user"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
                  "/api/v1/inventory/all/stack/entries",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "material",
                      "location",
                      "amount",
                      "quality",
                      "personal",
                      "note",
                      "user"))
              .addressedBy(
                  Set.of(
                      "materialId:string",
                      "locationId:string",
                      "userId:string",
                      "quality:integer",
                      "owningOrgUnitId:string",
                      "page:integer",
                      "size:integer")),
          new ContractOperation(
              "/api/v1/inventory",
              "post",
              Set.of("id", "material", "location", "amount", "quality", "personal"),
              Set.of("amount", "locationId")),
          new ContractOperation(
              "/api/v1/inventory/{id}/book-out",
              "post",
              Set.of("id", "material", "location", "amount", "personal"),
              Set.of("amount", "version")),
          new ContractOperation(
              "/api/v1/inventory/{id}/personal-rebook",
              "post",
              Set.of("id", "material", "location", "amount", "personal"),
              Set.of("amount", "version")),
          new ContractOperation(
              "/api/v1/inventory/{id}/note", "put", Set.of("id", "note"), Set.of("version")),
          new ContractOperation(
                  "/api/v1/materials/search",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "name",
                      "quantityType"))
              .addressedBy(Set.of("search:string", "page:integer", "size:integer")),
          new ContractOperation(
                  "/api/v1/locations/search",
                  "get",
                  Set.of("content", "page", "totalElements", "totalPages", "id", "name"))
              .addressedBy(Set.of("search:string", "page:integer", "size:integer")),
          new ContractOperation(
                  "/api/v1/users/search",
                  "get",
                  Set.of("content", "page", "totalElements", "totalPages", "id", "effectiveName"))
              .addressedBy(Set.of("query:string", "page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/materials/{id}/terminals",
              "get",
              Set.of("terminalId", "terminalName", "priceSell")),
          new ContractOperation("/api/v1/live-sync/stream", "get", Set.of())
              .addressedBy(Set.of("topics:string")),
          new ContractOperation(
              "/api/v1/live-sync/changed", "post", Set.of(), Set.of("topic", "sections")),
          new ContractOperation(
              "/api/v1/promotion/evaluations/my",
              "get",
              Set.of("categoryName", "topicName", "assignedLevel")),
          new ContractOperation(
              "/api/v1/promotion/eligibility/my",
              "get",
              Set.of(
                  "fromRank",
                  "toRank",
                  "eligible",
                  "hasConfiguredRules",
                  "checks",
                  "topicName",
                  "categoryName",
                  "minimumLevel",
                  "requiredCount",
                  "achievedCount",
                  "satisfied")),
          new ContractOperation(
              "/api/v1/app/version-policy",
              "get",
              Set.of("minimumVersionCode", "latestVersionCode", "releasesUrl")),
          new ContractOperation(
                  "/api/v1/refinery-orders/my-orders",
                  "get",
                  Set.of(
                      "content",
                      "totalElements",
                      "totalPages",
                      "id",
                      "status",
                      "location",
                      "refiningMethod",
                      "startedAt",
                      "durationMinutes",
                      "endsAt",
                      "goods",
                      "oreSales",
                      "profit",
                      "version"))
              .addressedBy(Set.of("status:array", "page:integer", "size:integer")),
          new ContractOperation(
                  "/api/v1/refinery-orders/all",
                  "get",
                  Set.of(
                      "content",
                      "totalElements",
                      "totalPages",
                      "id",
                      "owner",
                      "status",
                      "location",
                      "refiningMethod",
                      "startedAt",
                      "durationMinutes",
                      "endsAt",
                      "goods",
                      "oreSales",
                      "profit",
                      "version"))
              .addressedBy(Set.of("status:array", "page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/refinery-orders/{id}",
              "get",
              Set.of(
                  "id",
                  "status",
                  "location",
                  "refiningMethod",
                  "startedAt",
                  "durationMinutes",
                  "goods",
                  "oreSales",
                  "profit",
                  "version")),
          new ContractOperation(
              "/api/v1/refinery-orders/{id}/store", "post", Set.of(), Set.of("items")),
          new ContractOperation(
                  "/api/v1/material-exchange/offers",
                  "get",
                  Set.of(
                      "content",
                      "totalElements",
                      "totalPages",
                      "id",
                      "kind",
                      "material",
                      "quantityType",
                      "itemName",
                      "itemQuantity",
                      "owner",
                      "effectiveName",
                      "ownerOrgUnits",
                      "shorthand",
                      "mine",
                      "quality",
                      "amount",
                      "releasedAt",
                      "remark",
                      "interestCount",
                      "interestedHandles",
                      "viewerInterested",
                      "version"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
                  "/api/v1/material-requests",
                  "get",
                  Set.of(
                      "content",
                      "totalElements",
                      "totalPages",
                      "id",
                      "kind",
                      "material",
                      "quantityType",
                      "itemName",
                      "itemQuantity",
                      "requestedAmount",
                      "minQuality",
                      "owner",
                      "effectiveName",
                      "ownerOrgUnits",
                      "shorthand",
                      "mine",
                      "postedAt",
                      "remark",
                      "interestCount",
                      "interestedHandles",
                      "viewerInterested",
                      "version"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/material-exchange/offers/{id}/interest",
              "post",
              Set.of("id", "interestCount", "viewerInterested", "version")),
          new ContractOperation(
              "/api/v1/material-exchange/offers/{id}/interest",
              "delete",
              Set.of("id", "interestCount", "viewerInterested", "version")),
          new ContractOperation(
              "/api/v1/material-requests/{id}/interest",
              "post",
              Set.of("id", "interestCount", "viewerInterested", "version")),
          new ContractOperation(
              "/api/v1/material-requests/{id}/interest",
              "delete",
              Set.of("id", "interestCount", "viewerInterested", "version")),
          new ContractOperation(
              "/api/v1/material-exchange/offers/{id}/deactivate", "post", Set.of("id", "status")),
          new ContractOperation(
              "/api/v1/material-requests/{id}/deactivate", "post", Set.of("id", "status")),
          new ContractOperation(
              "/api/v1/material-exchange/offers",
              "post",
              Set.of(),
              Set.of("inventoryItemId", "offeredAmount")),
          new ContractOperation(
              "/api/v1/material-requests",
              "post",
              Set.of(),
              Set.of("materialId", "requestedAmount")),
          new ContractOperation(
                  "/api/v1/material-exchange/releasable-items",
                  "get",
                  Set.of(
                      "inventoryItemId",
                      "materialName",
                      "quantityType",
                      "quality",
                      "amount",
                      "locationName",
                      "alreadyReleased"))
              .addressedBy(Set.of("q:string", "kind:string")),
          new ContractOperation(
              "/api/v1/bank/accounts/{id}",
              "get",
              Set.of("account", "id", "accountNo", "name", "balance", "delta30d", "bookingCount")),
          new ContractOperation(
              "/api/v1/bank/accounts/{id}",
              "patch",
              Set.of("id", "accountNo", "name", "type", "status", "balance", "orgUnit", "version"),
              Set.of("name", "version")),
          new ContractOperation(
              "/api/v1/bank/accounts/{id}/close",
              "post",
              Set.of("id", "accountNo", "name", "type", "status", "balance", "orgUnit", "version"),
              Set.of("version")),
          new ContractOperation(
              "/api/v1/bank/accounts/{id}/reopen",
              "post",
              Set.of("id", "accountNo", "name", "type", "status", "balance", "orgUnit", "version"),
              Set.of("version")),
          new ContractOperation(
                  "/api/v1/bank/accounts/{id}/transactions",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "postingId",
                      "transactionId",
                      "type",
                      "amount",
                      "note",
                      "holderHandle",
                      "createdAt",
                      "reversedTransactionId",
                      "transferFee",
                      "counterpartyHandle"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation("/api/v1/bank/accounts/{id}/statement", "get", Set.of())
              .addressedBy(Set.of("from:string", "to:string")),
          new ContractOperation("/api/v1/bank/export/three-month-report", "get", Set.of()),
          new ContractOperation(
                  "/api/v1/bank/grants",
                  "get",
                  Set.of(
                      "userId",
                      "userHandle",
                      "accountId",
                      "canDeposit",
                      "canWithdraw",
                      "canTransfer",
                      "version"))
              .addressedBy(Set.of("accountId:string")),
          new ContractOperation(
              "/api/v1/bank/grants",
              "post",
              Set.of(
                  "userId",
                  "userHandle",
                  "accountId",
                  "canDeposit",
                  "canWithdraw",
                  "canTransfer",
                  "version"),
              Set.of("userId", "accountId")),
          new ContractOperation(
              "/api/v1/bank/grants/{userId}/{accountId}",
              "patch",
              Set.of(
                  "userId",
                  "userHandle",
                  "accountId",
                  "canDeposit",
                  "canWithdraw",
                  "canTransfer",
                  "version"),
              Set.of("canDeposit", "canWithdraw", "canTransfer", "version")),
          new ContractOperation("/api/v1/bank/grants/{userId}/{accountId}", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/bank/holders/transfer",
              "post",
              Set.of(),
              Set.of("sourceHolderId", "destinationHolderId", "amount")),
          new ContractOperation(
                  "/api/v1/bank/holders/{id}/transactions",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "postingId",
                      "transactionId",
                      "type",
                      "amount",
                      "note",
                      "createdAt",
                      "counterAccountNo",
                      "counterAccountName",
                      "counterHolderHandle",
                      "reversedTransactionId"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
                  "/api/v1/bank/requests",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "accountId",
                      "accountName",
                      "targetAccountId",
                      "type",
                      "amount",
                      "note",
                      "justification",
                      "status",
                      "requesterHandle",
                      "rejectReason",
                      "applicableLimit",
                      "requiresOwnerApproval",
                      "ownerApprovalGranted",
                      "ownerApprovalGrantedByHandle",
                      "requiredApprover",
                      "createdAt",
                      "version"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/bank/requests/{id}/confirm",
              "post",
              Set.of(
                  "id",
                  "accountId",
                  "accountName",
                  "targetAccountId",
                  "type",
                  "amount",
                  "note",
                  "justification",
                  "status",
                  "requesterHandle",
                  "rejectReason",
                  "applicableLimit",
                  "requiresOwnerApproval",
                  "requiredApprover",
                  "ownerApprovalGranted",
                  "ownerApprovalGrantedByHandle",
                  "createdAt",
                  "version"),
              Set.of("holderId", "version")),
          new ContractOperation(
              "/api/v1/bank/requests/{id}/reject",
              "post",
              Set.of(
                  "id",
                  "accountId",
                  "accountName",
                  "targetAccountId",
                  "type",
                  "amount",
                  "note",
                  "justification",
                  "status",
                  "requesterHandle",
                  "rejectReason",
                  "applicableLimit",
                  "requiresOwnerApproval",
                  "requiredApprover",
                  "ownerApprovalGranted",
                  "ownerApprovalGrantedByHandle",
                  "createdAt",
                  "version"),
              Set.of("reason", "version")),
          new ContractOperation("/api/v1/bank/transactions/{id}/reversal", "post", Set.of()),
          new ContractOperation("/api/v1/orders/items", "post", Set.of("id"), Set.of("items")),
          new ContractOperation(
              "/api/v1/refinery-orders", "post", Set.of("id"), Set.of("goods", "location")),
          new ContractOperation(
              "/api/v1/orders/{jobOrderId}/material-collection",
              "get",
              Set.of(
                  "inventoryEntryId",
                  "version",
                  "ownerName",
                  "ownerId",
                  "location",
                  "locationId",
                  "materialName",
                  "quality",
                  "quantity",
                  "allocatedQuantity",
                  "delivered")),
          new ContractOperation(
              "/api/v1/orders/{jobOrderId}/inventory/{inventoryItemId}/unlink", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/orders/{jobOrderId}/materials/{materialId}", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/inventory/{id}/delivered",
              "patch",
              Set.of(),
              Set.of("delivered", "jobOrderId", "version")),
          new ContractOperation(
              "/api/v1/missions/{id}/units/{missionUnitId}/crew/{crewId}/slim", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/bank/deposits", "post", Set.of(), Set.of("accountId", "amount", "holderId")),
          new ContractOperation(
              "/api/v1/bank/withdrawals",
              "post",
              Set.of("pendingRequest"),
              Set.of("accountId", "amount", "holderId")),
          new ContractOperation(
              "/api/v1/bank/transfers",
              "post",
              Set.of("pendingRequest"),
              Set.of(
                  "amount",
                  "destinationAccountId",
                  "destinationHolderId",
                  "sourceAccountId",
                  "sourceHolderId")),
          new ContractOperation("/api/v1/bank/transfer-fee-rate", "get", Set.of("rate")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/approval-limit/all-members",
              "put",
              BANK_ACCOUNT_SETTINGS,
              Set.of("limit")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/approval-limit/all-members",
              "delete",
              BANK_ACCOUNT_SETTINGS),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/approval-limit/area-members",
              "put",
              BANK_ACCOUNT_SETTINGS,
              Set.of("limit")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/approval-limit/area-members",
              "delete",
              BANK_ACCOUNT_SETTINGS),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/approval-limit/role/{roleCode}",
              "put",
              BANK_ACCOUNT_SETTINGS,
              Set.of("limit")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/approval-limit/role/{roleCode}",
              "delete",
              BANK_ACCOUNT_SETTINGS),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/approval-limit/user/{userId}",
              "put",
              BANK_ACCOUNT_SETTINGS,
              Set.of("limit")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/approval-limit/user/{userId}",
              "delete",
              BANK_ACCOUNT_SETTINGS),
          new ContractOperation(
              "/api/v1/users/me/payout-preference",
              "get",
              Set.of("defaultPayoutPreference", "version")),
          new ContractOperation(
              "/api/v1/users/me/payout-preference",
              "put",
              Set.of("defaultPayoutPreference", "version"),
              Set.of("preference", "version")),
          new ContractOperation(
              "/api/v1/users/me/blueprint-sharing",
              "get",
              Set.of("shareBlueprintsGlobally", "version")),
          new ContractOperation(
              "/api/v1/users/me/blueprint-sharing",
              "put",
              Set.of("shareBlueprintsGlobally", "version"),
              Set.of("shareBlueprintsGlobally", "version")),
          new ContractOperation(
              "/api/v1/users/me/read-announcement/{announcementId}",
              "put",
              Set.of("lastReadAnnouncementId")),
          new ContractOperation(
              "/api/v1/orders/{id}", "put", JOB_ORDER_DETAIL, Set.of("materials")),
          new ContractOperation(
              "/api/v1/operations/{id}", "put", Set.of(), Set.of("name", "status", "version")),
          new ContractOperation(
              "/api/v1/orders/lookup",
              "get",
              Set.of("id", "displayId", "handle", "requiredMaterialIds", "requiredGameItemIds")),
          new ContractOperation("/api/v1/missions/lookup", "get", Set.of("id", "name", "status")),
          new ContractOperation("/api/v1/operations/lookup", "get", Set.of("id", "name")),
          new ContractOperation(
                  "/api/v1/job-types", "get", Set.of("content", "id", "name", "active"))
              .addressedBy(Set.of("archetype:string", "page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/orders/material-demand",
              "get",
              Set.of(
                  "groups",
                  "orgUnit",
                  "id",
                  "name",
                  "shorthand",
                  "materials",
                  "material",
                  "qualityRequirement",
                  "requiredAmount",
                  "bookedAmount",
                  "claimedAmount",
                  "outstandingAmount",
                  "orders")),
          new ContractOperation(
              "/api/v1/orders/{jobOrderId}/item-stock",
              "get",
              Set.of(
                  "gameItem",
                  "id",
                  "name",
                  "orderedAmount",
                  "manufacturedAmount",
                  "allocatedTotal")),
          new ContractOperation(
              "/api/v1/orders/{jobOrderId}/claims",
              "get",
              Set.of(
                  "material",
                  "id",
                  "name",
                  "quantityType",
                  "qualityRequirement",
                  "requiredAmount",
                  "claimedAmount",
                  "openRemaining",
                  "claims",
                  "claimingOrgUnit",
                  "shorthand",
                  "amount")),
          new ContractOperation(
              "/api/v1/orders/{jobOrderId}/claims",
              "post",
              Set.of(),
              Set.of("amount", "claimingOrgUnitId", "materialId", "qualityRequirement")),
          new ContractOperation("/api/v1/orders/{jobOrderId}/claims/{claimId}", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/orders/{id}/materials/{matId}/inventory",
              "get",
              Set.of(
                  "id",
                  "user",
                  "effectiveName",
                  "displayName",
                  "location",
                  "name",
                  "quality",
                  "amount",
                  "jobOrderAllocations",
                  "jobOrderId",
                  "version")),
          new ContractOperation(
              "/api/v1/orders/{id}/handovers",
              "post",
              Set.of(),
              Set.of("handoverTime", "items", "recipientHandle")),
          new ContractOperation(
              "/api/v1/orders/{id}/item-handovers",
              "post",
              Set.of(),
              Set.of("entries", "handoverTime", "recipientHandle")),
          new ContractOperation(
              "/api/v1/orders/{id}/items/{itemId}/production",
              "post",
              Set.of(),
              Set.of("amount", "bookIn", "consumption", "version")),
          new ContractOperation("/api/v1/orders/{id}/items", "put", Set.of(), Set.of("items")),
          new ContractOperation("/api/v1/orders/{id}/priority", "put", JOB_ORDER_DETAIL)
              .addressedBy(Set.of("priority:integer")),
          new ContractOperation(
              "/api/v1/inventory/bulk-checkout", "post", Set.of(), Set.of("itemIds")),
          new ContractOperation(
              "/api/v1/inventory/bulk-rebook",
              "post",
              Set.of("rebooked", "skipped"),
              Set.of("itemIds", "mode")),
          new ContractOperation(
              "/api/v1/inventory/{id}/allocation",
              "post",
              INVENTORY_ROW,
              Set.of("field", "targetId")),
          new ContractOperation(
              "/api/v1/inventory/{id}/allocation",
              "patch",
              INVENTORY_ROW,
              Set.of("field", "targetId")),
          new ContractOperation(
              "/api/v1/inventory/{id}/allocation",
              "delete",
              INVENTORY_ROW,
              Set.of("field", "targetId")),
          new ContractOperation(
              "/api/v1/missions/{id}/core", "patch", MISSION_DETAIL, Set.of("name", "version")),
          new ContractOperation(
              "/api/v1/missions/{id}/schedule", "patch", MISSION_DETAIL, Set.of("version")),
          new ContractOperation(
              "/api/v1/missions/{id}/flags",
              "patch",
              MISSION_DETAIL,
              Set.of("isInternal", "version")),
          new ContractOperation(
              "/api/v1/missions/{id}/party-lead", "put", MISSION_DETAIL, Set.of("version")),
          new ContractOperation(
              "/api/v1/missions/{id}/participants/by-id/slim", "post", Set.of(), Set.of("userId")),
          new ContractOperation(
              "/api/v1/missions/{id}/unit-ship-options", "get", Set.of("id", "name", "shipType")),
          new ContractOperation(
              "/api/v1/missions/{id}/units/slim", "post", Set.of(), Set.of("name")),
          new ContractOperation(
              "/api/v1/missions/{id}/units/{unitId}/slim", "put", Set.of(), Set.of("name")),
          new ContractOperation("/api/v1/missions/{id}/units/{unitId}/slim", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/missions/{id}/units/{missionUnitId}/crew/slim",
              "post",
              Set.of(),
              Set.of("participantId")),
          new ContractOperation(
              "/api/v1/missions/{id}/units/{missionUnitId}/crew/{crewId}/slim", "put", Set.of()),
          new ContractOperation(
              "/api/v1/missions/{id}/units/{missionUnitId}/crew/{crewId}/slim", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/missions/{id}/frequencies/custom/slim",
              "post",
              Set.of("id", "frequencyType", "name", "value"),
              Set.of("name", "value")),
          new ContractOperation(
              "/api/v1/missions/{id}/frequencies/{frequencyId}/slim", "delete", Set.of()),
          new ContractOperation("/api/v1/missions/{id}/managers/{userId}/slim", "post", Set.of()),
          new ContractOperation("/api/v1/missions/{id}/managers/{userId}/slim", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/missions/{id}/steps/slim",
              "post",
              STEP_ROW,
              Set.of("stepsVersion", "title")),
          new ContractOperation(
              "/api/v1/missions/{id}/steps/{stepId}/slim",
              "put",
              STEP_ROW,
              Set.of("stepsVersion", "title")),
          new ContractOperation("/api/v1/missions/{id}/steps/{stepId}/slim", "delete", STEP_ROW)
              .addressedBy(Set.of("stepsVersion:integer")),
          new ContractOperation(
              "/api/v1/missions/{id}/steps/{stepId}/done/slim",
              "patch",
              STEP_ROW,
              Set.of("done", "stepsVersion")),
          new ContractOperation(
              "/api/v1/missions/{id}/steps/reorder/slim",
              "put",
              STEP_ROW,
              Set.of("stepIds", "stepsVersion")),
          new ContractOperation(
              "/api/v1/missions/{id}/objectives/slim",
              "post",
              OBJECTIVE_ROW,
              Set.of("kind", "objectivesVersion", "title")),
          new ContractOperation(
              "/api/v1/missions/{id}/objectives/{objectiveId}/slim",
              "put",
              OBJECTIVE_ROW,
              Set.of("kind", "objectivesVersion", "title")),
          new ContractOperation(
                  "/api/v1/missions/{id}/objectives/{objectiveId}/slim", "delete", OBJECTIVE_ROW)
              .addressedBy(Set.of("objectivesVersion:integer")),
          new ContractOperation(
              "/api/v1/missions/{id}/objectives/reorder/slim",
              "put",
              OBJECTIVE_ROW,
              Set.of("objectiveIds", "objectivesVersion")),
          new ContractOperation(
                  "/api/v1/materials/prices-overview",
                  "get",
                  Set.of(
                      "content",
                      "id",
                      "name",
                      "category",
                      "minPriceBuy",
                      "maxPriceSell",
                      "isIllegal"))
              .addressedBy(Set.of("name:string", "page:integer", "size:integer", "sort:string")),
          new ContractOperation(
              "/api/v1/materials/{id}",
              "get",
              Set.of("id", "name", "type", "quantityType", "category", "isIllegal")),
          new ContractOperation(
                  "/api/v1/materials/{id}/prices",
                  "get",
                  Set.of("content", "id", "terminalName", "priceBuy", "priceSell"))
              .addressedBy(Set.of("page:integer", "size:integer", "sort:string")),
          new ContractOperation(
                  "/api/v1/materials/profit-calculation",
                  "get",
                  Set.of(
                      "materialName",
                      "minBuyPrice",
                      "maxSellPrice",
                      "profitPerScu",
                      "fullLoadCost",
                      "maxProfitFullLoad",
                      "marginPercent"))
              .addressedBy(Set.of("shipId:string", "starSystemNames:array")),
          new ContractOperation(
                  "/api/v1/terminals", "get", Set.of("content", "starSystemName", "totalPages"))
              .addressedBy(Set.of("page:integer", "size:integer", "sort:string")),
          new ContractOperation("/api/v1/material-exchange/released-item-ids", "get", Set.of())
              .addressedBy(Set.of("ids:array")),
          new ContractOperation(
              "/api/v1/material-exchange/item-offers",
              "post",
              Set.of(),
              Set.of("productKey", "quantity")),
          new ContractOperation(
              "/api/v1/material-requests/item", "post", Set.of(), Set.of("productKey", "quantity")),
          new ContractOperation(
              "/api/v1/material-exchange/offers/{id}/remark",
              "put",
              Set.of(),
              Set.of("offeredAmount", "version")),
          new ContractOperation(
              "/api/v1/material-requests/{id}",
              "put",
              Set.of(),
              Set.of("desiredAmount", "version")),
          new ContractOperation(
              "/api/v1/personal-blueprints/import/preview",
              "post",
              Set.of(
                  "entries",
                  "externalName",
                  "status",
                  "productKey",
                  "productName",
                  "suggestedAcquiredAt"),
              Set.of("file")),
          new ContractOperation(
              "/api/v1/personal-blueprints/import/apply",
              "post",
              Set.of("added", "skipped", "alreadyOwned"),
              Set.of("resolutions")),
          new ContractOperation(
              "/api/v1/personal-blueprints/batch",
              "post",
              Set.of("added", "skippedAlreadyOwned", "skippedUnresolved"),
              Set.of("productKeys")),
          new ContractOperation(
                  "/api/v1/personal-blueprints/overview",
                  "get",
                  Set.of(
                      "content",
                      "productKey",
                      "productName",
                      "ownerCount",
                      "page",
                      "totalPages",
                      "totalElements"))
              .addressedBy(Set.of("page:integer", "size:integer", "sort:string", "search:string")),
          new ContractOperation(
                  "/api/v1/personal-blueprints/overview/owners",
                  "get",
                  Set.of("ownerName", "orgUnitMember"))
              .addressedBy(Set.of("productKey:string")),
          new ContractOperation(
              "/api/v1/hangar/import/fleetview",
              "post",
              Set.of("importedCount", "skippedCount", "duplicateCount"),
              Set.of("file")),
          new ContractOperation(
              "/api/v1/hangar/ships/home-location", "post", Set.of(), Set.of("locationId")),
          new ContractOperation("/api/v1/settings/{key}", "get", Set.of("value")));

  /**
   * Contract operations the app deliberately addresses with no query parameter although the
   * document declares one, exempting them from the query-parameter coverage guard.
   */
  private static final Set<String> ADDRESSED_BY_NO_QUERY_PARAMETER =
      Set.of(
          "get /api/v1/users/me/memberships",
          "get /api/v1/refining-methods",
          "post /api/v1/personal-blueprints/import/preview",
          "post /api/v1/personal-blueprints/import/apply",
          "post /api/v1/personal-blueprints/batch",
          "post /api/v1/hangar/import/fleetview",
          "post /api/v1/hangar/ships/home-location",
          "post /api/v1/material-exchange/item-offers",
          "post /api/v1/material-requests/item",
          "put /api/v1/material-exchange/offers/{id}/remark",
          "put /api/v1/material-requests/{id}",
          "get /api/v1/materials/{id}",
          "post /api/v1/inventory/bulk-checkout",
          "post /api/v1/inventory/bulk-rebook",
          "post /api/v1/inventory/{id}/allocation",
          "patch /api/v1/inventory/{id}/allocation",
          "delete /api/v1/inventory/{id}/allocation",
          "get /api/v1/orders/material-demand",
          "get /api/v1/orders/{jobOrderId}/item-stock",
          "get /api/v1/orders/{jobOrderId}/claims",
          "post /api/v1/orders/{jobOrderId}/claims",
          "delete /api/v1/orders/{jobOrderId}/claims/{claimId}",
          "get /api/v1/orders/{id}/materials/{matId}/inventory",
          "post /api/v1/orders/{id}/handovers",
          "post /api/v1/orders/{id}/item-handovers",
          "post /api/v1/orders/{id}/items/{itemId}/production",
          "put /api/v1/orders/{id}/items",
          "get /api/v1/orders/lookup");

  @Test
  @DisplayName("the query parameters a shipped client asks with still exist, with their types")
  void theContractQueryParametersAreFrozen() throws IOException {
    JsonNode document = openapi();

    for (ContractOperation operation : CONTRACT) {
      if (operation.queryParams().isEmpty()) {
        continue;
      }
      assertThat(queryParameters(document, operation))
          .as(
              "%s %s lost a query parameter the app addresses it by, or changed its type. The"
                  + " installed build keeps sending it: a renamed one is silently ignored and the"
                  + " member gets the wrong rows, a retyped one comes back 400 and the screen says"
                  + " it could not load. Neither is fixable without a new APK",
              operation.method().toUpperCase(java.util.Locale.ROOT), operation.path())
          .containsAll(operation.queryParams());
    }
  }

  @Test
  @DisplayName("no operation joins the set with its query parameters left unrecorded")
  void everyOperationWithQueryParametersStatesThem() throws IOException {
    JsonNode document = openapi();

    for (ContractOperation operation : CONTRACT) {
      String key = operation.method() + " " + operation.path();
      if (!operation.queryParams().isEmpty() || ADDRESSED_BY_NO_QUERY_PARAMETER.contains(key)) {
        continue;
      }
      assertThat(queryParameters(document, operation))
          .as(
              "%s %s takes query parameters and freezes none. Record the ones the app sends with"
                  + " addressedBy(...), or name it in ADDRESSED_BY_NO_QUERY_PARAMETER and say why"
                  + " the app sends nothing — an unanswered slot reads as a decision and is not"
                  + " one (REQ-API-009)",
              operation.method().toUpperCase(java.util.Locale.ROOT), operation.path())
          .isEmpty();
    }
  }

  /**
   * Returns the query parameters an operation declares, as {@code name:type}.
   *
   * @param document the parsed API document
   * @param operation the contract operation to resolve
   * @return the declared query parameters; an array's element type is omitted
   */
  private static Set<String> queryParameters(JsonNode document, ContractOperation operation) {
    JsonNode node = document.get("paths").path(operation.path()).path(operation.method());
    assertThat(node.isMissingNode())
        .as(
            "%s %s is in the contract set but not in the document",
            operation.method(), operation.path())
        .isFalse();
    Set<String> declared = new TreeSet<>();
    for (JsonNode parameter : node.path("parameters")) {
      if (!"query".equals(parameter.path("in").asString(""))) {
        continue;
      }
      declared.add(
          parameter.path("name").asString("")
              + ":"
              + parameter.path("schema").path("type").asString(""));
    }
    return declared;
  }

  /**
   * Constants of required enum properties reachable from the contract, keyed {@code
   * Schema.property}.
   *
   * <p>The Android client fails the whole response on an unknown constant in a required enum, and a
   * renamed request constant causes a 400, so changing one requires shipping an app build first.
   * Nullable enums are excluded because they degrade to {@code null}.
   */
  private static final Map<String, Set<String>> FROZEN_REQUIRED_ENUMS =
      Map.ofEntries(
          Map.entry("JobTypeDto.archetype", Set.of("CREW", "MISSION")),
          Map.entry(
              "PersonalInventoryItemCreateRequest.locationType", Set.of("CITY", "SPACE_STATION")),
          Map.entry(
              "PersonalInventoryItemUpdateRequest.locationType", Set.of("CITY", "SPACE_STATION")),
          Map.entry(
              "UpdateJobOrderStatusDto.status",
              Set.of("OPEN", "IN_PROGRESS", "REJECTED", "COMPLETED")),
          Map.entry("UpdatePayoutPreferenceRequest.preference", Set.of("PAYOUT", "DONATE")),
          Map.entry("MyPayoutPreferenceRequest.preference", Set.of("PAYOUT", "DONATE")),
          Map.entry("MissionFinanceEntryCreateDto.type", Set.of("INCOME", "EXPENSE")),
          Map.entry("MissionFinanceEntryUpdateDto.type", Set.of("INCOME", "EXPENSE")),
          Map.entry("CreateBankBookingRequest.type", Set.of("DEPOSIT", "WITHDRAWAL", "TRANSFER")),
          Map.entry("CreateJobOrderItemMaterialDto.quality", Set.of("GOOD", "NONE")),
          Map.entry("AddMissionObjectiveRequest.kind", Set.of("PRIMARY", "SECONDARY", "NON_GOAL")),
          Map.entry(
              "UpdateMissionObjectiveRequest.kind", Set.of("PRIMARY", "SECONDARY", "NON_GOAL")),
          Map.entry("BulkRebookRequest.mode", Set.of("LOCATION", "PERSONALIZE", "DEPERSONALIZE")),
          Map.entry("InventoryAllocationWriteDto.field", Set.of("JOB_ORDER", "MISSION")),
          Map.entry("CreateClaimDto.qualityRequirement", Set.of("GOOD", "NONE")),
          Map.entry(
              "OperationUpdateDto.status", Set.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELED")));

  @Test
  @DisplayName("no enum a shipped client must parse has gained or lost a constant")
  void theContractRequiredEnumsAreFrozen() throws IOException {
    JsonNode document = openapi();
    Map<String, Set<String>> actual = requiredEnumsReachableFromTheContract(document);

    assertThat(actual)
        .as(
            "a REQUIRED enum reachable from the contract set changed. An unknown constant does not"
                + " cost the field, it fails the WHOLE response for a client that parses it"
                + " strictly — every screen built on that operation goes dark on an installed app"
                + " that cannot be redeployed. Ship an app build that knows the constant first,"
                + " then add it here in the same PR that adds it to the enum")
        .isEqualTo(FROZEN_REQUIRED_ENUMS);
  }

  /**
   * Collects every required enum property reachable from the contract's request and response
   * schemas, walking the schema graph transitively including array items.
   *
   * @param document the parsed API document
   * @return {@code Schema.property} to its sorted constants; empty when nothing qualifies
   */
  private static Map<String, Set<String>> requiredEnumsReachableFromTheContract(JsonNode document) {
    JsonNode schemas = document.get("components").get("schemas");
    Map<String, Set<String>> found = new TreeMap<>();
    Set<String> visited = new TreeSet<>();
    for (ContractOperation operation : CONTRACT) {
      for (String root : responseSchemaNames(document, operation)) {
        walkSchema(schemas, root, visited, found);
      }
      String request = requestSchemaName(document, operation);
      if (request != null) {
        walkSchema(schemas, request, visited, found);
      }
    }
    return found;
  }

  /**
   * Names the schema an operation's JSON request body resolves to.
   *
   * @param document the parsed API document
   * @param operation the contract operation
   * @return the schema name, or {@code null} for an operation that carries no JSON body
   */
  private static String requestSchemaName(JsonNode document, ContractOperation operation) {
    JsonNode body =
        document
            .get("paths")
            .get(operation.path())
            .get(operation.method())
            .path("requestBody")
            .path("content")
            .path("application/json")
            .path("schema");
    return schemaName(body);
  }

  /**
   * Names the schemas an operation's 2xx responses resolve to.
   *
   * @param document the parsed API document
   * @param operation the contract operation
   * @return the schema names, following an array response through its {@code items}
   */
  private static Set<String> responseSchemaNames(JsonNode document, ContractOperation operation) {
    JsonNode responses =
        document.get("paths").get(operation.path()).get(operation.method()).get("responses");
    Set<String> names = new TreeSet<>();
    for (Map.Entry<String, JsonNode> response : responses.properties()) {
      if (!response.getKey().startsWith("2")) {
        continue;
      }
      JsonNode content = response.getValue().get("content");
      if (content == null) {
        continue;
      }
      for (Map.Entry<String, JsonNode> mediaType : content.properties()) {
        JsonNode schema = mediaType.getValue().path("schema");
        String name = schemaName(schema);
        if (name == null) {
          name = schemaName(schema.path("items"));
        }
        if (name != null) {
          names.add(name);
        }
      }
    }
    return names;
  }

  /**
   * Visits one schema and everything it reaches, recording its required enum properties.
   *
   * @param schemas the document's schema catalogue
   * @param name the schema to visit
   * @param visited names already walked, so a cyclic graph terminates
   * @param found the accumulator, keyed {@code Schema.property}
   */
  private static void walkSchema(
      JsonNode schemas, String name, Set<String> visited, Map<String, Set<String>> found) {
    walkProperties(
        schemas,
        name,
        visited,
        (owner, property, value, required) -> {
          String target = schemaName(value);
          JsonNode enumNode = target != null ? schemas.path(target).get("enum") : value.get("enum");
          if (enumNode != null && required) {
            Set<String> constants = new TreeSet<>();
            enumNode.forEach(entry -> constants.add(entry.asString()));
            found.put(owner + "." + property, constants);
          }
        });
  }

  /** What a traversal does with one property of one schema. */
  @FunctionalInterface
  private interface PropertyVisitor {

    /**
     * Called once per property of every schema the walk reaches.
     *
     * @param owner the schema the property belongs to
     * @param property the property name
     * @param value the property's schema node
     * @param required whether the owning schema lists it as required
     */
    void visit(String owner, String property, JsonNode value, boolean required);
  }

  /**
   * Walks a schema and everything it references transitively, via {@code $ref}, array {@code items}
   * and map {@code additionalProperties}, calling the visitor for each property. Shared by the enum
   * guard and the type record.
   *
   * @param schemas the document's {@code components.schemas} node
   * @param name the schema to walk; {@code null} and already-visited names are no-ops
   * @param visited the shared cycle guard
   * @param visitor what to do with each property
   */
  private static void walkProperties(
      JsonNode schemas, String name, Set<String> visited, PropertyVisitor visitor) {
    if (name == null || !visited.add(name)) {
      return;
    }
    JsonNode schema = schemas.get(name);
    if (schema == null) {
      return;
    }
    Set<String> required = new TreeSet<>();
    JsonNode requiredNode = schema.get("required");
    if (requiredNode != null) {
      requiredNode.forEach(entry -> required.add(entry.asString()));
    }
    JsonNode properties = schema.get("properties");
    if (properties == null) {
      return;
    }
    for (Map.Entry<String, JsonNode> property : properties.properties()) {
      JsonNode value = property.getValue();
      visitor.visit(name, property.getKey(), value, required.contains(property.getKey()));
      if ("array".equals(value.path("type").asString(""))) {
        walkProperties(schemas, schemaName(value.path("items")), visited, visitor);
        continue;
      }
      if (value.path("additionalProperties").isObject()) {
        walkProperties(schemas, schemaName(value.path("additionalProperties")), visited, visitor);
        continue;
      }
      walkProperties(schemas, schemaName(value), visited, visitor);
    }
  }

  /**
   * Reads a schema node's {@code $ref} target name.
   *
   * @param node the schema node
   * @return the referenced schema's name, or {@code null} when the node is not a reference
   */
  private static String schemaName(JsonNode node) {
    JsonNode ref = node == null ? null : node.get("$ref");
    return ref == null ? null : ref.asString().substring(ref.asString().lastIndexOf('/') + 1);
  }

  /**
   * Where the frozen type/nullability record lives, on the test classpath.
   *
   * <p>Beside {@code openapi.json} rather than under {@code src/test/java}, because it is data: a
   * contract change is reviewed as a sorted diff of this file, and a 1,479-entry Java literal would
   * be longer than the test that reads it.
   */
  private static final String FROZEN_TYPES_RESOURCE = "/api/frozen-contract-types.txt";

  /**
   * System property naming a previous release's {@code openapi.json} to diff against; set by CI,
   * absent locally.
   */
  private static final String BASELINE_PROPERTY = "contract.baseline";

  /**
   * Verifies that no field reachable from the contract changed its type, format or {@code required}
   * status against the frozen record (REQ-API-009).
   *
   * <p>Nullability is expressed only by {@code required}, as the document carries no {@code
   * nullable} keyword.
   *
   * @throws IOException if the committed document or the frozen record cannot be read
   */
  @Test
  @DisplayName("no frozen field changed its type, its format, or whether it is required")
  void theContractTypesAndNullabilityAreFrozen() throws IOException {
    Map<String, String> actual = contractSignatures(openapi());
    Map<String, String> frozen = readFrozenTypes();

    assertThat(frozen)
        .as("the frozen record is empty or unreadable, which would make this case vacuous")
        .hasSizeGreaterThan(500);

    Map<String, String> changed = new TreeMap<>();
    for (Map.Entry<String, String> entry : actual.entrySet()) {
      String was = frozen.get(entry.getKey());
      if (was != null && !was.equals(entry.getValue())) {
        changed.put(entry.getKey(), was + " -> " + entry.getValue());
      }
    }
    Set<String> gone = new TreeSet<>(frozen.keySet());
    gone.removeAll(actual.keySet());
    Set<String> added = new TreeSet<>(actual.keySet());
    added.removeAll(frozen.keySet());

    assertThat(changed)
        .as(
            "a frozen contract field changed its type, its format, or whether it is required. A"
                + " shipped Android build cannot be redeployed with the server: string -> object"
                + " fails the parse, and required -> optional turns a field an installed build"
                + " reads unconditionally into a null. Ship an app build that accepts the new"
                + " shape FIRST, then update "
                + FROZEN_TYPES_RESOURCE)
        .isEmpty();

    assertThat(gone)
        .as(
            "a frozen contract field is gone from the document. If the operation was retired,"
                + " remove its entries from "
                + FROZEN_TYPES_RESOURCE
                + " in the same change; if it was not, this is the break the guard exists for")
        .isEmpty();

    assertThat(added)
        .as(
            "the contract grew fields that are not frozen. Additions are safe for a shipped"
                + " client, but an unfrozen field is an unguarded one — append them to "
                + FROZEN_TYPES_RESOURCE)
        .isEmpty();
  }

  /**
   * Verifies that no contract field changed shape since the previous release, comparing only
   * properties present in both documents (ADR-0136). Skipped when no baseline is configured.
   *
   * @throws IOException if either document cannot be read
   */
  @Test
  @DisplayName("no field a released build reads changed shape since that release")
  void theContractTypesMatchThePreviousRelease() throws IOException {
    String baseline = System.getProperty(BASELINE_PROPERTY);
    Assumptions.assumeTrue(
        baseline != null && Files.isReadable(Path.of(baseline)),
        "no -Dcontract.baseline pointing at a readable previous-release openapi.json; the frozen"
            + " record in theContractTypesAndNullabilityAreFrozen covers this run");

    JsonNode previous = new ObjectMapper().readTree(Files.readString(Path.of(baseline)));
    Map<String, String> was = contractSignatures(previous);
    Map<String, String> now = contractSignatures(openapi());

    assertThat(was)
        .as("the baseline document yielded no contract signatures, so this case proves nothing")
        .isNotEmpty();

    Map<String, String> changed = new TreeMap<>();
    for (Map.Entry<String, String> entry : now.entrySet()) {
      String before = was.get(entry.getKey());
      if (before != null && !before.equals(entry.getValue())) {
        changed.put(entry.getKey(), before + " -> " + entry.getValue());
      }
    }

    Set<String> gone = new TreeSet<>(was.keySet());
    gone.removeAll(now.keySet());

    assertThat(changed)
        .as(
            "a field changed shape since the last release, and there are builds in the field that"
                + " read it. This is the diff ADR-0136 asks for, against an artefact this pull"
                + " request cannot edit")
        .isEmpty();

    assertThat(gone)
        .as(
            "a field the last release served is gone from the document. An installed build reads it"
                + " unconditionally and will now get null \u2014 the break the freeze exists for."
                + " If the operation was retired deliberately, the app build that stops reading it"
                + " ships FIRST")
        .isEmpty();
  }

  /**
   * Records the type, format and required-ness of every property reachable from the contract set.
   *
   * @param document the parsed API document
   * @return {@code Schema.property} to its signature, sorted
   */
  private static Map<String, String> contractSignatures(JsonNode document) {
    JsonNode schemas = document.path("components").path("schemas");
    Map<String, String> found = new TreeMap<>();
    Set<String> visited = new TreeSet<>();
    for (ContractOperation operation : CONTRACT) {
      if (document.path("paths").path(operation.path()).path(operation.method()).isMissingNode()) {
        continue;
      }
      for (String root : responseSchemaNames(document, operation)) {
        walkSignatures(schemas, root, visited, found);
      }
      walkSignatures(schemas, requestSchemaName(document, operation), visited, found);
      recordInlineBodies(document, operation, found);
    }
    return found;
  }

  /**
   * Freezes body shapes that resolve to no named schema: multipart request bodies and inline 2xx
   * response schemas, keyed by operation and media type.
   *
   * @param document the parsed API document
   * @param operation the contract operation
   * @param found the accumulator
   */
  private static void recordInlineBodies(
      JsonNode document, ContractOperation operation, Map<String, String> found) {
    JsonNode node = document.path("paths").path(operation.path()).path(operation.method());
    String key = operation.method().toUpperCase(java.util.Locale.ROOT) + " " + operation.path();

    for (Map.Entry<String, JsonNode> media :
        node.path("requestBody").path("content").properties()) {
      JsonNode schema = media.getValue().path("schema");
      found.put(key + " request[" + media.getKey() + "]", signature(schema));
      for (String required : requiredNames(schema)) {
        found.put(key + " request[" + media.getKey() + "].required." + required, "required");
      }
    }

    for (Map.Entry<String, JsonNode> response : node.path("responses").properties()) {
      if (!response.getKey().startsWith("2")) {
        continue;
      }
      for (Map.Entry<String, JsonNode> media : response.getValue().path("content").properties()) {
        found.put(
            key + " response[" + response.getKey() + "][" + media.getKey() + "]",
            signature(media.getValue().path("schema")));
      }
    }
  }

  /**
   * The {@code required} entries an inline schema declares.
   *
   * @param schema the schema node
   * @return the required property names, sorted; empty when the node declares none
   */
  private static Set<String> requiredNames(JsonNode schema) {
    Set<String> names = new TreeSet<>();
    JsonNode required = schema.path("required");
    if (required.isArray()) {
      required.forEach(entry -> names.add(entry.asString()));
    }
    return names;
  }

  /**
   * Adds one signature per property of {@code name}, then follows every reference it makes.
   *
   * @param schemas the document's {@code components.schemas} node
   * @param name the schema to record; {@code null} and already-visited names are no-ops
   * @param visited the shared cycle guard
   * @param found the accumulator
   */
  private static void walkSignatures(
      JsonNode schemas, String name, Set<String> visited, Map<String, String> found) {
    walkProperties(
        schemas,
        name,
        visited,
        (owner, property, value, required) ->
            found.put(owner + "." + property, signature(value) + (required ? "!" : "")));
  }

  /**
   * The one-line shape of a property node.
   *
   * @param node the property's schema node
   * @return {@code $Ref}, {@code array<...>}, {@code type/format} or {@code type}
   */
  private static String signature(JsonNode node) {
    String ref = schemaName(node);
    if (ref != null) {
      return "$" + ref;
    }
    String type = node.path("type").asString("");
    if ("array".equals(type)) {
      JsonNode items = node.path("items");
      String itemRef = schemaName(items);
      return "array<" + (itemRef != null ? "$" + itemRef : signature(items)) + ">";
    }
    JsonNode additional = node.path("additionalProperties");
    if (additional.isObject()) {
      String value = schemaName(additional);
      return "map<" + (value != null ? "$" + value : signature(additional)) + ">";
    }
    if (type.isEmpty()) {
      return node.has("properties") ? "object" : "any";
    }
    String format = node.path("format").asString("");
    return format.isEmpty() ? type : type + "/" + format;
  }

  /**
   * Reads the committed type record off the test classpath.
   *
   * @return {@code Schema.property} to signature, comments and blank lines dropped
   * @throws IOException if the resource is missing or unreadable
   */
  private static Map<String, String> readFrozenTypes() throws IOException {
    Map<String, String> frozen = new TreeMap<>();
    try (java.io.InputStream in =
        ExternalContractTest.class.getResourceAsStream(FROZEN_TYPES_RESOURCE)) {
      if (in == null) {
        throw new IOException("missing test resource " + FROZEN_TYPES_RESOURCE);
      }
      for (String line :
          new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).split("\\R")) {
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) {
          continue;
        }
        int split = trimmed.indexOf('=');
        if (split < 0) {
          throw new IOException("malformed line in " + FROZEN_TYPES_RESOURCE + ": " + trimmed);
        }
        frozen.put(trimmed.substring(0, split), trimmed.substring(split + 1));
      }
    }
    return frozen;
  }

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
  @DisplayName("no contract request has gained a field an old build does not send")
  void theContractRequestsKeepTheirRequiredFields() throws IOException {
    JsonNode document = openapi();

    for (ContractOperation operation : CONTRACT) {
      Set<String> required = requiredRequestFields(document, operation);
      assertThat(required)
          .as(
              "%s %s requires a request field the recorded contract does not. A shipped app cannot"
                  + " learn to send it; add the field as optional, or version the endpoint"
                  + " (REQ-API-009, ADR-0136)",
              operation.method().toUpperCase(java.util.Locale.ROOT), operation.path())
          .containsExactlyInAnyOrderElementsOf(operation.requiredRequestFields());
    }
  }

  /**
   * Reads the {@code required} list of an operation's request body schema, following the first
   * media type's {@code $ref}.
   *
   * @param document the parsed API document
   * @param operation the contract operation to resolve
   * @return the required property names; empty when there is no body or nothing is required
   */
  private static Set<String> requiredRequestFields(JsonNode document, ContractOperation operation) {
    JsonNode body =
        document.get("paths").get(operation.path()).get(operation.method()).get("requestBody");
    if (body == null) {
      return Set.of();
    }
    JsonNode content = body.get("content");
    if (content == null || !content.properties().iterator().hasNext()) {
      return Set.of();
    }
    JsonNode schema = content.properties().iterator().next().getValue().get("schema");
    String name = schemaName(schema);
    JsonNode resolved = name == null ? schema : document.get("components").get("schemas").get(name);
    JsonNode required = resolved == null ? null : resolved.get("required");
    Set<String> fields = new TreeSet<>();
    if (required != null) {
      required.forEach(entry -> fields.add(entry.asString()));
    }
    return fields;
  }

  @Test
  void theContractSetIsNotSilentlyEmptied() {
    assertThat(CONTRACT).hasSizeGreaterThanOrEqualTo(5);
  }

  /**
   * Collects the property names of an operation's 2xx response schema, following its {@code $ref}
   * or its array items' {@code $ref}, plus the properties of schemas referenced by array
   * properties.
   *
   * @param document the parsed API document
   * @param operation the contract operation to resolve
   * @return the property names, or an empty set when the response has no body schema
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
          properties.addAll(nestedProperties(document, schemaProperties));
        }
      }
    }
    return properties;
  }

  /**
   * Collects the property names of schemas referenced by a response's properties, two levels deep,
   * into one flat set.
   *
   * @param document the parsed API document
   * @param objectProperties the properties of the already-resolved response schema
   * @return the referenced schemas' property names, or an empty set when nothing is referenced
   */
  private static Set<String> nestedProperties(JsonNode document, JsonNode objectProperties) {
    Set<String> nested = new TreeSet<>();
    collectNested(document, objectProperties, MAX_NESTING, nested);
    return nested;
  }

  /**
   * Adds the properties of every schema {@code objectProperties} references, down to {@code depth}.
   *
   * @param document the parsed API document
   * @param objectProperties the properties to descend from
   * @param depth how many further levels to follow; zero stops the walk
   * @param into the accumulator
   */
  private static void collectNested(
      JsonNode document, JsonNode objectProperties, int depth, Set<String> into) {
    if (depth <= 0 || objectProperties == null) {
      return;
    }
    for (Map.Entry<String, JsonNode> property : objectProperties.properties()) {
      JsonNode ref = property.getValue().path("items").get("$ref");
      if (ref == null) {
        ref = property.getValue().get("$ref");
      }
      if (ref == null) {
        continue;
      }
      String name = ref.asString().substring(ref.asString().lastIndexOf('/') + 1);
      JsonNode schema = document.get("components").get("schemas").get(name);
      JsonNode properties = schema == null ? null : schema.get("properties");
      if (properties != null) {
        into.addAll(properties.propertyNames());
        collectNested(document, properties, depth - 1, into);
      }
    }
  }

  /**
   * Verifies that every frozen operation is admitted by the API vhost allow-list, parsed from the
   * nginx include.
   *
   * @throws IOException if the allow-list cannot be read
   */
  @Test
  @DisplayName("every frozen operation is admitted by the API vhost allow-list")
  void theFrozenSetIsReachableThroughTheEdge() throws IOException {
    List<Predicate<String>> rules = allowListRules();
    assertThat(rules)
        .as("no allow-list rules were parsed from %s — has its format changed?", ALLOW_LIST)
        .hasSizeGreaterThan(50);

    List<String> unreachable =
        CONTRACT.stream()
            .map(ContractOperation::path)
            .distinct()
            .filter(path -> rules.stream().noneMatch(rule -> rule.test(probePath(path))))
            .sorted()
            .toList();

    assertThat(unreachable)
        .as(
            "these operations are frozen as a promise to a shipped client but no rule in %s admits "
                + "them, so the edge answers 404 and the promise cannot be called. Add the rule in "
                + "the same change that freezes the operation — and if one of these merely uses a "
                + "path placeholder this test does not know how to fill, extend PLACEHOLDERS",
            ALLOW_LIST)
        .isEmpty();
  }

  /**
   * Parses the allow-list into predicates over a concrete URI: {@code $uri = "…"} as an exact
   * match, {@code $uri ~ "…"} as a regular expression; other lines are ignored.
   *
   * @return one predicate per parsed rule
   * @throws IOException if the allow-list cannot be read
   */
  private static List<Predicate<String>> allowListRules() throws IOException {
    Path allowList = findRepoRoot().resolve(ALLOW_LIST);
    assertThat(Files.exists(allowList)).as("%s must exist", allowList).isTrue();

    List<Predicate<String>> rules = new java.util.ArrayList<>();
    for (String line : Files.readAllLines(allowList)) {
      if (!line.contains("krt_api_allowed 1")) {
        continue;
      }
      Matcher exact = EXACT_RULE.matcher(line);
      if (exact.find()) {
        String literal = exact.group(1);
        rules.add(literal::equals);
        continue;
      }
      Matcher regex = REGEX_RULE.matcher(line);
      if (regex.find()) {
        Pattern compiled = Pattern.compile(regex.group(1));
        rules.add(uri -> compiled.matcher(uri).find());
      }
    }
    return rules;
  }

  /**
   * Replaces each placeholder in a contract path with a representative value: a UUID by default, or
   * the value named in {@link #PLACEHOLDERS}.
   *
   * @param path the contract path, possibly containing {@code {name}} segments
   * @return the path with every placeholder replaced
   */
  private static String probePath(String path) {
    String probe = path;
    for (var entry : PLACEHOLDERS.entrySet()) {
      probe = probe.replace(entry.getKey(), entry.getValue());
    }
    return PLACEHOLDER.matcher(probe).replaceAll(SAMPLE_UUID);
  }

  /**
   * Walks up from the working directory to the repository root.
   *
   * <p>Located by {@code settings.gradle.kts} so the allow-list resolves whichever module directory
   * the test task runs in.
   *
   * @return the repository root
   */
  private static Path findRepoRoot() {
    Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath();
    while (dir != null && !Files.exists(dir.resolve("settings.gradle.kts"))) {
      dir = dir.getParent();
    }
    assertThat(dir)
        .as("could not locate the repository root from %s", System.getProperty("user.dir"))
        .isNotNull();
    return dir;
  }

  /**
   * Verifies that no frozen operation declares a required header, which an installed app build may
   * not send.
   *
   * @throws IOException if the document cannot be read
   */
  @Test
  @DisplayName("no frozen operation requires a header an installed build may not send")
  void theContractRequiresNoHeader() throws IOException {
    JsonNode document = openapi();

    List<String> demanded = new java.util.ArrayList<>();
    for (ContractOperation operation : CONTRACT) {
      JsonNode paths = document.get("paths");
      JsonNode path = paths == null ? null : paths.get(operation.path());
      JsonNode verb = path == null ? null : path.get(operation.method());
      if (verb == null) {
        continue;
      }
      JsonNode parameters = verb.get("parameters");
      if (parameters == null) {
        continue;
      }
      for (JsonNode parameter : parameters) {
        boolean isHeader = "header".equals(parameter.path("in").asString(""));
        if (isHeader && parameter.path("required").asBoolean(false)) {
          demanded.add(
              operation.method().toUpperCase(java.util.Locale.ROOT)
                  + " "
                  + operation.path()
                  + " requires "
                  + parameter.path("name").asString(""));
        }
      }
    }

    assertThat(demanded)
        .as(
            "a frozen operation now requires a header. Every build already installed sends the "
                + "headers it was written against, so this is a 400 on every call it makes. Ship a "
                + "build that sends it first — or, if the app has always sent it unconditionally, "
                + "say so here rather than widening the rule")
        .isEmpty();
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
