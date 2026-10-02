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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.backend.config.ContractTiers;
import de.greluc.krt.profit.basetool.backend.config.OpenApiDomainConfig;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards the external contract set (REQ-API-009, ADR-0136): operations a shipped client depends on,
 * which may not change shape in place.
 *
 * <p>Reads the committed {@code openapi.json} and fails when a contract operation disappears,
 * changes its verb, loses a recorded response field, gains a required request field, changes a
 * frozen enum, or changes a recorded query parameter; when a committed app call list names a call
 * the set does not cover (REQ-API-016); and when anything frozen broke since the previous release
 * without a line in the declared-break ledger (REQ-API-017, ADR-0234).
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
   * The nginx include that decides what the internet can reach through the API vhost (ADR-0162).
   */
  private static final String ALLOW_LIST = "docker/edge/include/api-allowlist.conf";

  /**
   * One admission rule: {@code if ($uri = "…")}, {@code if ($uri ~ "…")} or {@code if ($uri ~*
   * "…")}, followed by {@code { set $krt_api_allowed 1; }}; group 1 is the operator, group 2 the
   * operand.
   */
  private static final Pattern ADMISSION_RULE =
      Pattern.compile(
          "^\\s*if\\s*\\(\\s*\\$uri\\s*(=|~\\*?)\\s*\"([^\"]+)\"\\s*\\)"
              + "\\s*\\{\\s*set\\s+\\$krt_api_allowed\\s+1\\s*;\\s*}\\s*$");

  /** {@code set $krt_api_allowed 0;}, the default every request starts from. */
  private static final Pattern DEFAULT_DENY =
      Pattern.compile("^\\s*set\\s+\\$krt_api_allowed\\s+0\\s*;\\s*$");

  /** {@code if ($krt_api_allowed = 0) { return 404; }}, the refusal of every unadmitted URI. */
  private static final Pattern UNADMITTED_REFUSAL =
      Pattern.compile(
          "^\\s*if\\s*\\(\\s*\\$krt_api_allowed\\s*=\\s*0\\s*\\)\\s*\\{\\s*return\\s+404\\s*;\\s*}\\s*$");

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

  /**
   * Response fields promised by every operation returning a {@code MissionDto}, since the app maps
   * them through one shared mapper. {@code user} identifies the caller's own participant row.
   */
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

  /**
   * The contract set: every operation the Android app consumes, matching the paths the API vhost
   * allow-lists, recorded from the generated document.
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
              .addressedBy(
                  Set.of(
                      "materialIds:array",
                      "stolenOnly:boolean",
                      "nonStolenOnly:boolean",
                      "catalog:string",
                      "locationIds:array")),
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
              .addressedBy(
                  Set.of("status:array", "page:integer", "size:integer", "squadronId:array")),
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
              .addressedBy(Set.of("page:integer", "size:integer", "sort:string")),
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
                      "stolen:boolean",
                      "owningOrgUnitId:string",
                      "catalog:string",
                      "gameItemId:string",
                      "page:integer",
                      "size:integer")),
          new ContractOperation(
                  "/api/v1/inventory/my-inventory/grouped",
                  "get",
                  Set.of(
                      "material",
                      "gameItem",
                      "totalAmount",
                      "averageQuality",
                      "maxQuality",
                      "stacks",
                      "user",
                      "location",
                      "quality",
                      "personal",
                      "stolen",
                      "owningSquadron",
                      "entryCount"))
              .addressedBy(
                  Set.of(
                      "locationIds:array",
                      "personalOnly:boolean",
                      "nonPersonalOnly:boolean",
                      "stolenOnly:boolean",
                      "nonStolenOnly:boolean",
                      "catalog:string")),
          new ContractOperation(
                  "/api/v1/inventory/my-inventory/stack/entries",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "id",
                      "material",
                      "gameItem",
                      "location",
                      "amount",
                      "quality",
                      "personal",
                      "stolen",
                      "owningSquadron",
                      "canEdit",
                      "note",
                      "version"))
              .addressedBy(
                  Set.of(
                      "materialId:string",
                      "gameItemId:string",
                      "locationId:string",
                      "quality:integer",
                      "personal:boolean",
                      "stolen:boolean",
                      "owningOrgUnitId:string",
                      "catalog:string",
                      "page:integer",
                      "size:integer")),
          new ContractOperation("/api/v1/inventory/my-inventory/entry-ids", "get", Set.of())
              .addressedBy(
                  Set.of(
                      "locationIds:array",
                      "personalOnly:boolean",
                      "nonPersonalOnly:boolean",
                      "stolenOnly:boolean",
                      "nonStolenOnly:boolean",
                      "catalog:string")),
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
              .addressedBy(
                  Set.of(
                      "search:string",
                      "page:integer",
                      "size:integer",
                      "jobOrderOnly:boolean",
                      "rawOnly:boolean")),
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
              .addressedBy(Set.of("status:array", "page:integer", "size:integer", "sort:string")),
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
              .addressedBy(Set.of("page:integer", "size:integer", "status:array")),
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
              "/api/v1/users/me/rsi-handle", "get", Set.of("rsiHandle", "version")),
          new ContractOperation(
              "/api/v1/users/me/rsi-handle",
              "put",
              Set.of("rsiHandle", "version"),
              Set.of("version")),
          new ContractOperation(
              "/api/v1/users/me/read-announcement/{announcementId}",
              "put",
              Set.of("lastReadAnnouncementId")),
          new ContractOperation(
              "/api/v1/orders/{id}", "put", JOB_ORDER_DETAIL, Set.of("materials")),
          new ContractOperation(
              "/api/v1/operations/{id}", "put", Set.of(), Set.of("name", "status", "version")),
          new ContractOperation(
              "/api/v1/operations", "post", Set.of("id"), Set.of("name", "status")),
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
              "/api/v1/inventory/bulk-org-unit",
              "post",
              Set.of("changed", "skipped"),
              Set.of("itemIds")),
          new ContractOperation(
              "/api/v1/inventory/{id}/org-unit",
              "post",
              Set.of("id", "material", "location", "amount", "personal")),
          new ContractOperation(
              "/api/v1/inventory/bulk-stolen",
              "post",
              Set.of("changed", "skipped"),
              Set.of("itemIds", "stolen")),
          new ContractOperation(
              "/api/v1/inventory/{id}/stolen",
              "post",
              Set.of("id", "material", "location", "amount", "personal", "stolen"),
              Set.of("stolen")),
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
          new ContractOperation("/api/v1/settings/{key}", "get", Set.of("value")),
          new ContractOperation(
                  "/api/v1/materials/matrix",
                  "get",
                  Set.of(
                      "content",
                      "page",
                      "totalElements",
                      "totalPages",
                      "materialId",
                      "materialName",
                      "terminalId",
                      "terminalName",
                      "starSystemName",
                      "priceBuy",
                      "priceSell"))
              .addressedBy(Set.of("page:integer", "size:integer", "sort:string")),
          new ContractOperation("/api/v1/orders", "post", JOB_ORDER_DETAIL, Set.of("materials")),
          new ContractOperation(
              "/api/v1/orders/{id}/requested", "put", Set.of(), Set.of("materials")),
          new ContractOperation(
              "/api/v1/bank/accounts",
              "post",
              Set.of("id", "accountNo", "name", "type", "status", "balance", "version"),
              Set.of("name", "type")),
          new ContractOperation(
              "/api/v1/bank/holders",
              "post",
              Set.of("id", "userId", "handle", "active", "totalHeld", "version"),
              Set.of("userId")),
          new ContractOperation(
              "/api/v1/bank/holders/{id}",
              "patch",
              Set.of("id", "userId", "handle", "active", "totalHeld", "version"),
              Set.of("active", "version")),
          new ContractOperation(
              "/api/v1/refinery-orders/{id}", "put", Set.of(), Set.of("goods", "location")),
          new ContractOperation("/api/v1/refinery-orders/{id}", "delete", Set.of()),
          new ContractOperation("/api/v1/hangar/ships", "delete", Set.of()),
          new ContractOperation("/api/v1/personal-blueprints", "delete", Set.of("deleted")),
          new ContractOperation(
              "/api/v1/missions/{id}/participants/{participantId}/slim",
              "put",
              Set.of("id", "user", "guestName", "startTime", "endTime", "payoutPreference"),
              Set.of("version")));

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
          Map.entry(
              "CreateBankAccountRequest.type",
              Set.of("ORG_UNIT", "AREA", "CARTEL", "CARTEL_BANK", "SPECIAL")),
          Map.entry("CreateJobOrderItemMaterialDto.quality", Set.of("GOOD", "NONE")),
          Map.entry("AddMissionObjectiveRequest.kind", Set.of("PRIMARY", "SECONDARY", "NON_GOAL")),
          Map.entry(
              "UpdateMissionObjectiveRequest.kind", Set.of("PRIMARY", "SECONDARY", "NON_GOAL")),
          Map.entry("BulkRebookRequest.mode", Set.of("LOCATION", "PERSONALIZE", "DEPERSONALIZE")),
          Map.entry("InventoryAllocationWriteDto.field", Set.of("JOB_ORDER", "MISSION")),
          Map.entry("CreateClaimDto.qualityRequirement", Set.of("GOOD", "NONE")),
          Map.entry(
              "OperationCreateDto.status", Set.of("PLANNED", "ACTIVE", "COMPLETED", "CANCELED")),
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
      for (String root :
          OpenApiWalk.responseSchemaNames(document, operation.path(), operation.method())) {
        walkSchema(schemas, root, visited, found);
      }
      String request =
          OpenApiWalk.requestSchemaName(document, operation.path(), operation.method());
      if (request != null) {
        walkSchema(schemas, request, visited, found);
      }
    }
    return found;
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
    OpenApiWalk.walkProperties(
        schemas,
        name,
        visited,
        (owner, property, value, required) -> {
          String target = OpenApiWalk.schemaName(value);
          JsonNode enumNode = target != null ? schemas.path(target).get("enum") : value.get("enum");
          if (enumNode != null && required) {
            Set<String> constants = new TreeSet<>();
            enumNode.forEach(entry -> constants.add(entry.asString()));
            found.put(owner + "." + property, constants);
          }
        });
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
   * System property naming a previous release's {@code openapi.json} to diff against; set by the
   * build, pointing at a file only CI fetches.
   */
  private static final String BASELINE_PROPERTY = "contract.baseline";

  /**
   * System property that turns a missing baseline into a failure; the build sets it from {@code
   * CONTRACT_BASELINE_REQUIRED}, which CI sets.
   */
  private static final String BASELINE_REQUIRED_PROPERTY = "contract.baseline.required";

  /** The declared-break ledger on the test classpath (REQ-API-017). */
  private static final String LEDGER_RESOURCE = "/api/declared-breaks.txt";

  /** The committed app call lists, relative to the repository root (REQ-API-016). */
  private static final String APP_CALLS = "backend/src/test/resources/api/app-calls";

  /** How many calls the newest committed app call list names at least. */
  private static final int NEWEST_CALL_LIST_FLOOR = 243;

  /** How many operations the frozen set holds at least. */
  private static final int FROZEN_OPERATIONS_FLOOR = 246;

  /**
   * The record of tier {@code T0}, which never changes (ADR-0234): the version gate, the 14
   * exchange relay operations, the two streams and the live-sync signal. The SPI endpoint {@code
   * POST /internal/discord/account-existence} is T0 too but is not in the document.
   */
  private static final Set<String> TIER_ZERO =
      Set.of(
          "GET /api/v1/app/version-policy",
          "GET /api/v1/notifications/stream",
          "GET /api/v1/live-sync/stream",
          "POST /api/v1/live-sync/changed",
          "GET /api/v1/exchange/catalog/locations",
          "POST /api/v1/exchange/catalog/resolve",
          "POST /api/v1/exchange/me/account-check",
          "GET /api/v1/exchange/me/blueprints",
          "POST /api/v1/exchange/me/blueprints/changes",
          "POST /api/v1/exchange/me/drafts/blueprints",
          "POST /api/v1/exchange/me/drafts/refinery-orders",
          "GET /api/v1/exchange/me/installation",
          "POST /api/v1/exchange/me/installation",
          "GET /api/v1/exchange/me/org-demand",
          "GET /api/v1/exchange/me/ships",
          "POST /api/v1/exchange/me/ships/changes",
          "GET /api/v1/exchange/me/stock",
          "POST /api/v1/exchange/me/stock/changes");

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
   * Verifies that no frozen operation or field broke since the previous release unless the
   * declared-break ledger names exactly that break (REQ-API-017, ADR-0234).
   *
   * <p>Compares the operations of the frozen set, of every committed app call list and of the
   * ledger. Skipped only where no baseline is required; CI requires one.
   *
   * @throws IOException if either document or the ledger cannot be read
   */
  @Test
  @DisplayName("nothing a released build calls broke since that release unless the ledger says so")
  void theContractTypesMatchThePreviousRelease() throws IOException {
    Path baseline =
        requiredBaseline(
            System.getProperty(BASELINE_PROPERTY), Boolean.getBoolean(BASELINE_REQUIRED_PROPERTY));
    Assumptions.assumeTrue(
        baseline != null,
        "no -Dcontract.baseline pointing at a readable previous-release openapi.json, and none is"
            + " required here; the frozen record in theContractTypesAndNullabilityAreFrozen covers"
            + " this run");

    JsonNode previous = new ObjectMapper().readTree(Files.readString(baseline));
    assertThat(contractSignatures(previous))
        .as("the baseline document yielded no contract signatures, so this case proves nothing")
        .isNotEmpty();

    Map<DeclaredBreaks.Break, String> found =
        DeclaredBreaks.between(previous, openapi(), comparedOperations(previous));

    assertThat(DeclaredBreaks.undeclared(found, ledger()))
        .as(
            "a frozen operation or field broke since the last release, and builds in the field call"
                + " it. This is the diff against an artefact this pull request cannot edit. A"
                + " deliberate break of a hard-cut wave adds exactly these lines, each followed by"
                + " the versionCode of the app build that absorbs it, to %s (ADR-0234); anything"
                + " else is the break the freeze exists for",
            LEDGER_RESOURCE)
        .isEmpty();
  }

  /**
   * Verifies the baseline gate: where a baseline is required, a missing one fails instead of
   * skipping, and where it is not, a missing one skips.
   *
   * @param tempDir an empty directory for the fixture
   * @throws IOException if the fixture cannot be written
   */
  @Test
  @DisplayName("a missing previous-release baseline fails where one is required")
  void aMissingBaselineFailsWhereOneIsRequired(@TempDir Path tempDir) throws IOException {
    String missing = tempDir.resolve("openapi.json").toString();

    assertThatThrownBy(() -> requiredBaseline(missing, true))
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("required");
    assertThatThrownBy(() -> requiredBaseline(null, true)).isInstanceOf(AssertionError.class);
    assertThat(requiredBaseline(missing, false)).isNull();
    assertThat(requiredBaseline(null, false)).isNull();

    Path present = Files.writeString(tempDir.resolve("present.json"), "{}");
    assertThat(requiredBaseline(present.toString(), true)).isEqualTo(present);
  }

  /**
   * Resolves the previous-release baseline.
   *
   * @param baseline the configured path, or {@code null} when none is configured
   * @param required whether a missing baseline is a failure rather than a skip
   * @return the readable baseline, or {@code null} when it is missing and not required
   * @throws AssertionError if it is missing and required
   */
  private static @Nullable Path requiredBaseline(@Nullable String baseline, boolean required) {
    Path path = baseline == null ? null : Path.of(baseline);
    if (path != null && Files.isReadable(path)) {
      return path;
    }
    if (required) {
      throw new AssertionError(
          "-D"
              + BASELINE_REQUIRED_PROPERTY
              + "=true, but no readable previous-release openapi.json at "
              + baseline
              + ". The comparison with the last release is required here: a skipped one is a green"
              + " check that checked nothing. The CI step 'Fetch the previous release's API"
              + " contract' writes it to backend/build/contract-baseline/openapi.json");
    }
    return null;
  }

  /**
   * Collects the operations the previous-release comparison looks at: the frozen set, every call of
   * every committed app call list, every operation the ledger names, and every operation the
   * previous document marks {@code T0} or {@code T1}.
   *
   * @param previous the previous release's document
   * @return the operations, verb upper case
   * @throws IOException if a call list or the ledger cannot be read
   */
  private static Set<DeclaredBreaks.OperationKey> comparedOperations(JsonNode previous)
      throws IOException {
    Set<DeclaredBreaks.OperationKey> operations =
        new LinkedHashSet<>(DeclaredBreaks.frozenIn(previous));
    for (ContractOperation operation : CONTRACT) {
      operations.add(
          new DeclaredBreaks.OperationKey(
              operation.method().toUpperCase(Locale.ROOT), operation.path()));
    }
    for (AppCallList.Release release : appCallLists()) {
      for (AppCallList.Call call : release.calls()) {
        operations.add(new DeclaredBreaks.OperationKey(call.method(), call.path()));
      }
    }
    for (DeclaredBreaks.Entry entry : ledger()) {
      operations.add(
          new DeclaredBreaks.OperationKey(entry.declared().method(), entry.declared().path()));
    }
    return operations;
  }

  /**
   * Verifies the contract tiers (REQ-API-018, ADR-0234): the {@code T0} list equals the record that
   * never changes, the {@code T1} list is the frozen set minus {@code T0}, the document carries
   * each operation's tier, and no ledger line declares a {@code T0} break.
   *
   * @throws IOException if the document or the ledger cannot be read
   */
  @Test
  @DisplayName(
      "the contract tiers are T0 as recorded, T1 as frozen, and T0 is never declared broken")
  void theContractTiersMatchTheFrozenSet() throws IOException {
    ContractTiers tiers = ContractTiers.load();
    assertThat(tiers.operations(ContractTiers.T0))
        .as("the T0 list in %s changed; T0 never changes (ADR-0234)", ContractTiers.RESOURCE)
        .containsExactlyInAnyOrderElementsOf(TIER_ZERO);

    Set<String> frozenMinusTierZero = new TreeSet<>();
    for (ContractOperation operation : CONTRACT) {
      frozenMinusTierZero.add(operation.method().toUpperCase(Locale.ROOT) + " " + operation.path());
    }
    frozenMinusTierZero.removeAll(TIER_ZERO);
    assertThat(new TreeSet<>(tiers.operations(ContractTiers.T1)))
        .as(
            "the T1 list in %s is not the frozen set minus T0. Freezing and tiering are one"
                + " decision: change CONTRACT and the list together",
            ContractTiers.RESOURCE)
        .isEqualTo(frozenMinusTierZero);

    JsonNode document = openapi();
    List<String> wrongTier = new ArrayList<>();
    for (Map.Entry<String, JsonNode> path : document.path("paths").properties()) {
      for (Map.Entry<String, JsonNode> verb : path.getValue().properties()) {
        if (!verb.getValue().has("responses")) {
          continue;
        }
        String tier = verb.getValue().path(OpenApiDomainConfig.TIER_EXTENSION).asString("");
        String expected = tiers.tierOf(verb.getKey(), path.getKey());
        if (!expected.equals(tier)) {
          wrongTier.add(verb.getKey() + " " + path.getKey() + ": " + tier + " != " + expected);
        }
      }
    }
    assertThat(wrongTier).as("the committed document carries a stale x-contract-tier").isEmpty();

    assertThat(DeclaredBreaks.onTierZero(ledger(), TIER_ZERO))
        .as("%s declares a break of a T0 operation, which never breaks", LEDGER_RESOURCE)
        .isEmpty();
  }

  /**
   * Verifies that the declared-break ledger parses and agrees with the committed app call lists: no
   * list of a build the ledger walls off is still committed, and no list of an absorbing build
   * calls an operation the ledger declares gone (REQ-API-017).
   *
   * @throws IOException if the ledger or a call list cannot be read
   */
  @Test
  @DisplayName("the declared-break ledger parses and agrees with the committed app call lists")
  void theLedgerAgreesWithTheCommittedCallLists() throws IOException {
    assertThat(AppCallList.conflictsWithLedger(appCallLists(), ledger()))
        .as(
            "%s and the committed app call lists contradict each other. A break is absorbed by a"
                + " build that no longer makes the call, and every older build is walled off by the"
                + " floor the break raises (REQ-API-010)",
            LEDGER_RESOURCE)
        .isEmpty();
  }

  /**
   * Verifies that the frozen set covers every call of every committed app call list: the verb and
   * path are frozen, and so is every query parameter the app sends (REQ-API-016).
   *
   * @throws IOException if a call list cannot be read
   */
  @Test
  @DisplayName("the frozen set covers every call of every app build the server still serves")
  void theFrozenSetCoversEveryCallOfEveryServedAppBuild() throws IOException {
    List<AppCallList.Release> releases = appCallLists();
    assertThat(releases)
        .as("no app call list is committed under %s, so coverage would be vacuous", APP_CALLS)
        .isNotEmpty();
    assertThat(releases.getLast().calls())
        .as(
            "the newest app call list names fewer calls than it did when it was last reviewed; if"
                + " the app really dropped calls, lower this floor in the same change")
        .hasSizeGreaterThanOrEqualTo(NEWEST_CALL_LIST_FLOOR);

    Map<String, Set<String>> frozenQuery = new TreeMap<>();
    for (ContractOperation operation : CONTRACT) {
      Set<String> names = new TreeSet<>();
      operation.queryParams().forEach(parameter -> names.add(parameter.split(":", 2)[0]));
      frozenQuery.put(operation.method().toUpperCase(Locale.ROOT) + " " + operation.path(), names);
    }

    assertThat(AppCallList.uncovered(releases, frozenQuery))
        .as(
            "an app build the server still serves calls something the frozen set does not hold."
                + " Its edge admission, its query parameters and its required request fields are"
                + " then unguarded, and the next re-cut breaks a build nobody can redeploy. Add the"
                + " operation to CONTRACT with the parameters the list names (REQ-API-016)")
        .isEmpty();
  }

  /**
   * Verifies that the document still serves every response field a committed app call list says the
   * app may read, at the depth {@link #responseProperties} looks (REQ-API-016).
   *
   * @throws IOException if the document or a call list cannot be read
   */
  @Test
  @DisplayName("every response field a served app build may read is still in the document")
  void theFieldsEveryServedAppBuildReadsAreStillServed() throws IOException {
    JsonNode document = openapi();
    List<String> missing = new ArrayList<>();
    for (AppCallList.Release release : appCallLists()) {
      for (AppCallList.Call call : release.calls()) {
        if (OpenApiWalk.operation(document, call.path(), call.method()).isMissingNode()) {
          missing.add(release.name() + ": " + call.key() + " is not served at all");
          continue;
        }
        Set<String> absent = new TreeSet<>(call.fields());
        absent.removeAll(
            responseProperties(document, call.path(), call.method().toLowerCase(Locale.ROOT)));
        if (!absent.isEmpty()) {
          missing.add(release.name() + ": " + call.key() + " no longer answers " + absent);
        }
      }
    }

    assertThat(missing)
        .as(
            "a response field an app build in the field may read is gone. The call list over-states"
                + " rather than under-states what the app reads, so a name here may be unread"
                + " \u2014 but that is for the app's maintainers to say, in a new list, not for"
                + " this build to assume. A deliberate removal is a declared break (%s)",
            LEDGER_RESOURCE)
        .isEmpty();
  }

  /**
   * Loads every committed app call list.
   *
   * @return the lists sorted by {@code versionCode}, the unreleased build last
   * @throws IOException if the directory or a list cannot be read
   */
  private static List<AppCallList.Release> appCallLists() throws IOException {
    return AppCallList.load(findRepoRoot().resolve(APP_CALLS));
  }

  /**
   * Reads and parses the declared-break ledger off the test classpath.
   *
   * @return the ledger's entries, in file order
   * @throws IOException if the resource is missing or unreadable
   */
  private static List<DeclaredBreaks.Entry> ledger() throws IOException {
    try (InputStream in = ExternalContractTest.class.getResourceAsStream(LEDGER_RESOURCE)) {
      if (in == null) {
        throw new IOException("missing test resource " + LEDGER_RESOURCE);
      }
      return DeclaredBreaks.parse(
          List.of(new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\R", -1)));
    }
  }

  /**
   * Records the type, format and required-ness of every property reachable from the contract set,
   * plus every inline or multipart body keyed by its operation.
   *
   * @param document the parsed API document
   * @return {@code Schema.property} or {@code VERB path body-key} to its signature, sorted
   */
  private static Map<String, String> contractSignatures(JsonNode document) {
    Map<String, String> found = new TreeMap<>();
    for (ContractOperation operation : CONTRACT) {
      String key = operation.method().toUpperCase(Locale.ROOT) + " " + operation.path();
      OpenApiWalk.operationSignatures(document, operation.path(), operation.method())
          .forEach(
              (field, signature) ->
                  found.put(
                      field.startsWith("request[") || field.startsWith("response[")
                          ? key + " " + field
                          : field,
                      signature));
    }
    return found;
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
                  + " be redeployed. Retiring it is a declared break of a hard-cut wave (ADR-0234,"
                  + " REQ-API-017), not a silent deletion",
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
      Set<String> present = responseProperties(document, operation.path(), operation.method());
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
    String name = OpenApiWalk.schemaName(schema);
    JsonNode resolved = name == null ? schema : document.get("components").get("schemas").get(name);
    JsonNode required = resolved == null ? null : resolved.get("required");
    Set<String> fields = new TreeSet<>();
    if (required != null) {
      required.forEach(entry -> fields.add(entry.asString()));
    }
    return fields;
  }

  /**
   * Verifies that the frozen set holds at least the operations it held when the floor was last
   * raised, and names each operation once.
   */
  @Test
  @DisplayName("the frozen set is not silently emptied and names each operation once")
  void theContractSetIsNotSilentlyEmptied() {
    assertThat(CONTRACT)
        .as(
            "the frozen set shrank below its floor. Shrinking it is a declared break (REQ-API-017);"
                + " raise the floor whenever the set grows")
        .hasSizeGreaterThanOrEqualTo(FROZEN_OPERATIONS_FLOOR);
    assertThat(CONTRACT.stream().map(operation -> operation.method() + " " + operation.path()))
        .as("an operation is listed twice in CONTRACT")
        .doesNotHaveDuplicates();
  }

  /**
   * Collects the property names of an operation's 2xx response schema, following its {@code $ref}
   * or its array items' {@code $ref}, plus the properties of schemas referenced by array
   * properties.
   *
   * @param document the parsed API document
   * @param path the operation's path template
   * @param method the HTTP verb, lower case
   * @return the property names, or an empty set when the response has no body schema
   */
  private static Set<String> responseProperties(JsonNode document, String path, String method) {
    JsonNode responses = document.get("paths").get(path).get(method).get("responses");
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
   * Verifies that no exchange or connected-apps path is admitted by the API vhost allow-list: the
   * exchange layer answers only the gateway and the member controls only the browser (REQ-XCH-001,
   * ADR-0216). Probes every such path the committed {@code openapi.json} documents, plus paths no
   * controller serves yet, so a broad rule is caught before the endpoint it would expose exists.
   *
   * @throws IOException if the allow-list or the document cannot be read
   */
  @Test
  @DisplayName("no exchange or connected-apps path is admitted by the API vhost allow-list")
  void theExchangeStaysOffTheApiVhost() throws IOException {
    List<Predicate<String>> rules = allowListRules();
    List<String> documented =
        openapi().get("paths").propertyNames().stream()
            .filter(ExternalContractTest::isExchangeOrConnectionPath)
            .map(ExternalContractTest::probePath)
            .toList();
    assertThat(documented)
        .as("openapi.json documents no exchange path — has the document or its prefixes moved?")
        .contains("/api/v1/exchange/me/blueprints", "/api/v1/connected-apps");

    Set<String> probes = new TreeSet<>(documented);
    probes.addAll(
        List.of(
            "/api/v1/exchange",
            "/api/v1/exchange/",
            "/api/v1/exchange/me/a-resource-not-built-yet",
            "/api/v1/exchange/v2/me/blueprints",
            "/api/v1/connected-apps/",
            "/api/v1/connected-apps/versekit/undo",
            "/api/v1/connected-apps/a-control-not-built-yet",
            "/api/v1/admin/exchange-a-page-not-built-yet"));
    List<String> admitted =
        probes.stream().filter(path -> rules.stream().anyMatch(rule -> rule.test(path))).toList();

    assertThat(admitted)
        .as(
            "%s must never admit the exchange layer or the member's connection controls",
            ALLOW_LIST)
        .isEmpty();
  }

  /**
   * Tells whether a path belongs to the exchange layer, the member's connection controls or the
   * admin registry of connected applications.
   *
   * @param path a documented path
   * @return {@code true} for {@code /api/v1/exchange/**}, {@code /api/v1/connected-apps/**} and
   *     {@code /api/v1/admin/exchange-*}
   */
  private static boolean isExchangeOrConnectionPath(String path) {
    return path.equals("/api/v1/exchange")
        || path.startsWith("/api/v1/exchange/")
        || path.equals("/api/v1/connected-apps")
        || path.startsWith("/api/v1/connected-apps/")
        || path.startsWith("/api/v1/admin/exchange-");
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
   * Verifies that a line touching the admission flag in a form the parser does not know fails the
   * parse instead of being skipped, so no rule can admit a URI the exchange guard never probes.
   */
  @Test
  @DisplayName("an allow-list line the parser cannot read fails instead of being skipped")
  void anAllowListLineTheParserCannotReadFails() {
    List<String> unreadable =
        List.of(
            "if ($request_uri ~ \"^/api/v1/exchange/\") { set $krt_api_allowed 1; }",
            "if ($uri !~ \"^/api/v1/users/\") { set $krt_api_allowed 1; }",
            "if ($uri ~ ^/api/v1/exchange/) { set $krt_api_allowed 1; }",
            "if ($uri ~ \"^/api/v1/exchange/\") { set $krt_api_allowed \"1\"; }",
            "set $krt_api_allowed 1;",
            "    set $krt_api_allowed 1;");

    for (String line : unreadable) {
      assertThatThrownBy(() -> parseAllowList(List.of("set $krt_api_allowed 0;", line)))
          .as(line)
          .isInstanceOf(AssertionError.class)
          .hasMessageContaining("line 2");
    }
  }

  /**
   * Verifies that a {@code ~*} rule is parsed as the case-insensitive regular expression nginx
   * evaluates, so a rule written in upper case still counts as admitting the lower-case path.
   */
  @Test
  @DisplayName("a ~* allow-list rule is parsed as a case-insensitive regular expression")
  void aCaseInsensitiveRuleIsParsedCaseInsensitively() {
    List<Predicate<String>> rules =
        parseAllowList(
            List.of(
                "set $krt_api_allowed 0;",
                "if ($uri ~* \"^/API/V1/EXCHANGE/\") { set $krt_api_allowed 1; }",
                "if ($uri ~ \"^/API/V1/CONNECTED-APPS\") { set $krt_api_allowed 1; }",
                "if ($krt_api_allowed = 0) { return 404; }"));

    assertThat(rules).hasSize(2);
    assertThat(rules.get(0).test("/api/v1/exchange/me/blueprints")).isTrue();
    assertThat(rules.get(1).test("/api/v1/connected-apps")).isFalse();
  }

  /**
   * Reads the committed allow-list and parses it with {@link #parseAllowList(List)}.
   *
   * @return one predicate per admission rule
   * @throws IOException if the allow-list cannot be read
   */
  private static List<Predicate<String>> allowListRules() throws IOException {
    Path allowList = findRepoRoot().resolve(ALLOW_LIST);
    assertThat(Files.exists(allowList)).as("%s must exist", allowList).isTrue();
    return parseAllowList(Files.readAllLines(allowList));
  }

  /**
   * Parses allow-list lines into predicates over a concrete URI: {@code $uri = "…"} as an exact
   * match, {@code $uri ~ "…"} as a regular expression and {@code $uri ~* "…"} as a case-insensitive
   * one. Lines that do not mention {@code krt_api_allowed} are ignored.
   *
   * @param lines the lines of the nginx include
   * @return one predicate per admission rule, in file order
   * @throws AssertionError if a line mentioning {@code krt_api_allowed} is neither an admission
   *     rule, the default nor the refusal
   */
  private static List<Predicate<String>> parseAllowList(List<String> lines) {
    List<Predicate<String>> rules = new java.util.ArrayList<>();
    for (int index = 0; index < lines.size(); index++) {
      String line = lines.get(index);
      if (!line.contains("krt_api_allowed")) {
        continue;
      }
      Matcher rule = ADMISSION_RULE.matcher(line);
      if (rule.matches()) {
        String operand = rule.group(2);
        switch (rule.group(1)) {
          case "=" -> rules.add(operand::equals);
          case "~" -> rules.add(regexRule(Pattern.compile(operand)));
          default -> rules.add(regexRule(Pattern.compile(operand, Pattern.CASE_INSENSITIVE)));
        }
        continue;
      }
      if (!DEFAULT_DENY.matcher(line).matches() && !UNADMITTED_REFUSAL.matcher(line).matches()) {
        throw new AssertionError(
            String.format(
                "%s line %d touches krt_api_allowed in a form this test cannot evaluate, so it "
                    + "cannot prove the rule keeps the exchange off the API vhost: %s — write it "
                    + "as a one-line if ($uri = \"…\"), if ($uri ~ \"…\") or if ($uri ~* \"…\") "
                    + "rule, or teach parseAllowList the new form",
                ALLOW_LIST, index + 1, line.strip()));
      }
    }
    return rules;
  }

  /**
   * Wraps a compiled nginx regular expression as a predicate with nginx's unanchored semantics.
   *
   * @param compiled the rule's regular expression
   * @return a predicate that is true when the expression is found anywhere in the URI
   */
  private static Predicate<String> regexRule(Pattern compiled) {
    return uri -> compiled.matcher(uri).find();
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
