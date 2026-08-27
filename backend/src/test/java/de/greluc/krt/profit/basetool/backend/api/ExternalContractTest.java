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
import java.util.TreeMap;
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
 * operation disappears, changes its verb, loses a recorded response field, gains a required request
 * field, changes a required enum's constants, or renames or retypes a query parameter the app
 * addresses it by. Those are the breaks that silently reach a device.
 *
 * <p>It still does <em>not</em> compare response field types, nullability, or a parameter's default
 * value — the last of which is a real gap, since a list whose {@code sort} default flips reorders a
 * shipped screen with every name and type intact. Catching that needs a schema diff against the
 * previous release, which ADR-0136 records as the next step rather than pretending this covers it.
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
   * How far into a response's schema graph the field guard looks.
   *
   * <p>Two: a paged response spends the first level on its own rows, so a row's nested object — a
   * ship's {@code shipType}, whose {@code name} is the whole point of the card — needs the second.
   */
  private static final int MAX_NESTING = 2;

  /**
   * One frozen operation: path, verb, the response fields a shipped client may rely on, the query
   * parameters it addresses the operation by, and — for a write — the request fields the server may
   * demand of it.
   *
   * <p><b>Why the query parameters sit here and not in a list of their own.</b> They were held in a
   * side map keyed by {@code "method path"}, and the shape of that map was the defect: adding an
   * operation to {@code CONTRACT} did not oblige anyone to say how the app addresses it. Five
   * operations that take query parameters therefore reached the set with none recorded — a paged
   * Finanzen tab, the paged Hangar org overview, the offer sheet's picker, an optimistic lock
   * riding a {@code DELETE} as a query parameter, and one whose honest answer turned out to be
   * "none at all". As a component the slot travels with the entry, and the coverage guard fails the
   * build when it is left unanswered.
   *
   * @param path the {@code /api/v1} path exactly as it appears in the document
   * @param method the HTTP verb, lower case, as OpenAPI spells it
   * @param responseFields response properties that must keep existing; additive change is fine
   * @param requiredRequestFields the request body's {@code required} list, frozen exactly. Empty
   *     for an operation with no request body, and for one whose body is entirely optional
   * @param queryParams query parameters the app sends, as {@code name:type}, frozen as a subset so
   *     the server may still add optional ones. Empty for an operation the app addresses by path
   *     alone
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
     * A write whose request body has a {@code required} list to freeze.
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
     * <p>Written as a builder step rather than a fifth argument so the seventy-odd entries the app
     * reaches by path alone stay as they are, and the ones that take parameters name them where a
     * reviewer reads the entry.
     *
     * @param frozen the parameters as {@code name:type}, using the schema type the document
     *     declares; an array's element type is left out, since a client sends the same repeated
     *     parameter either way
     * @return a copy of this operation carrying the frozen parameters
     */
    ContractOperation addressedBy(Set<String> frozen) {
      return new ContractOperation(path, method, responseFields, requiredRequestFields, frozen);
    }
  }

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
                  // Phase 3 widened this: the app now acts on the caller's OWN participant row,
                  // and `user` is the only thing that says which row that is. A name cannot decide
                  // it — the server sends `displayName` when a member set one and `username`
                  // otherwise — and `startTime` is what "checked in" means on the wire.
                  "user",
                  "startTime",
                  "payoutPreference",
                  "assignedUnits",
                  "steps",
                  "objectives",
                  "frequencies")),
          // Phase 3, the four things a member does to their own participation. `join` answers with
          // the whole Einsatz because it creates the row; the three slim ones answer with the row
          // alone, which is the point of them — the detail is large and a check-in changes one
          // timestamp.
          //
          // The leave is the slim DELETE and not the legacy full one: both exist, the legacy pair
          // is `@ApiDeprecation`-marked with a sunset, and freezing a deprecated path would be a
          // promise the backend has already announced it will not keep.
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
          // PAYOUT / DONATE, and the request half is where the app's own copy of those two words
          // lives. Required on the request, so the enum guard covers it.
          new ContractOperation(
              "/api/v1/missions/{id}/participants/{participantId}/payout-preference/slim",
              "put",
              Set.of("id", "payoutPreference"),
              Set.of("preference")),
          // The Finanzen tab. Unlike the two above it this one is NOT anonymous:
          // `isAuthenticated() and isMemberOrAbove() and canSeeMission(#missionId)`, so it answers
          // 403 to an anonymous caller and to a guest alike -- not 401, because the chain is
          // permitAll here and the refusal happens at the method seam (REQ-SEC-037, pinned by
          // ApiVhostAnonymousSurfaceTest).
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
              // Paged like every other list the app scrolls: the envelope above is frozen, so the
              // two
              // parameters that reach page two belong to the same contract. `sort` is left alone --
              // the
              // app takes the server's `createdAt,desc` default, as it does on the inbox and the
              // ledger.
              .addressedBy(Set.of("page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/missions/{missionId}/finance-entries/summary",
              "get",
              Set.of("total", "incomeSum", "incomeCount", "expenseSum", "expenseCount")),
          // Phase 3, booking money against an Einsatz. The write paths are
          // `/api/v1/finance-entries`
          // — NOT under `/missions` — so they are their own family on the vhost rather than an
          // exception to the read-only guard on that one.
          //
          // `participant` and `version` join the read for the same reason the assignee edge did on
          // an order: the app may only edit the caller's own entry, and both halves of that
          // sentence need a field. The nested participant is stripped of PII by the controller on
          // the create response, so what is frozen here is the id and nothing else about them.
          new ContractOperation(
              "/api/v1/finance-entries",
              "post",
              Set.of("id", "missionId", "participant", "type", "amount", "note", "version"),
              Set.of("amount", "missionId", "participantId", "type")),
          // The update requires `version` where the create cannot have one, and that asymmetry is
          // the optimistic lock: an entry is edited against the copy the client read.
          new ContractOperation(
              "/api/v1/finance-entries/{entryId}",
              "put",
              Set.of("id", "missionId", "participant", "type", "amount", "note", "version"),
              Set.of("amount", "type", "version")),
          new ContractOperation("/api/v1/finance-entries/{entryId}", "delete", Set.of()),
          // The payout confirmation. Two roles in one gate: MISSION_MANAGER may mark a share paid
          // out, and only an OFFICER or ADMIN may take that back — the app offers the first and
          // lets the server refuse the second, because it cannot know which of the two the caller
          // is from `/users/me` alone.
          new ContractOperation(
              "/api/v1/operations/{id}/payouts/paid-out",
              "put",
              Set.of("participantKey", "paidOut", "paidOutAt", "paidOutByName"),
              Set.of("participantKey")),
          // Phase 2, the Lager tree. Two reads, one per level: the aggregate is the group
          // row a member sees first, and the grouped read fills a group they opened. Neither is
          // `/inventory/all`, which is the flat entry list -- a tree that fetched every leaf to
          // draw its roots would pull the whole warehouse to show a dozen headings.
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
          // Phase 2, the Aufträge queue and one order in full. `redacted` is frozen because it is
          // the field that tells the screen it is looking at a reduced order (REQ-ORDERS-023): a
          // requester sees their own order without the parts that are not theirs, and a client
          // that stopped seeing the flag would present the gaps as the whole truth.
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
          // Phase 3 widened this one rather than adding a second entry: the assignee edge is what
          // the app now writes to, and it is reached through this response. `note` and `version`
          // are the edge's own -- the version is NOT the order's, and sending the order's would
          // 409 every note edit -- and `effectiveName` is the only name a row can show. `user` and
          // `assignees` are the containers they arrive in; without them the app cannot tell whose
          // edge it is holding, which is what decides "assign me" from "unassign me".
          new ContractOperation(
              "/api/v1/orders/{id}",
              "get",
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
                  "requestingOrgUnit",
                  "responsibleOrgUnit")),
          // Phase 3, the two writes any member may make on an order they can see: putting their
          // own name on it and taking it off again. Self-assignment is open to everyone;
          // assigning someone else needs LOGISTICIAN, which the app never attempts.
          //
          // The response is the whole order, and what the app reads back from it is the refreshed
          // assignee list plus the version -- the list is redrawn from the answer rather than
          // guessed at, because the server decides the order of it.
          new ContractOperation(
              "/api/v1/orders/{id}/assignees/{userId}",
              "post",
              Set.of("id", "assignees", "user", "effectiveName", "note", "version")),
          new ContractOperation(
              "/api/v1/orders/{id}/assignees/{userId}",
              "delete",
              Set.of("id", "assignees", "user", "effectiveName", "note", "version")),
          // The assignee's own note -- when they work on it, which part they take. Locked on the
          // EDGE's version, which is why nothing here is required: a client that has never seen a
          // version may omit it and take the last write, and a client that has one sends it and
          // gets a 409 instead of overwriting a colleague.
          new ContractOperation(
              "/api/v1/orders/{id}/assignees/{userId}/note",
              "put",
              Set.of("id", "assignees", "user", "effectiveName", "note", "version"),
              Set.of()),
          new ContractOperation(
                  "/api/v1/orders/{id}/assignees/{userId}/note",
                  "delete",
                  Set.of("id", "assignees", "user", "effectiveName", "note", "version"))
              // The optimistic lock, and it travels as a QUERY parameter here where the PUT twin
              // carries
              // it in the body. A null version skips the check server-side, so a renamed parameter
              // does
              // not fail: every note deletion in the field silently stops being locked and takes
              // the last
              // write over a colleague's edit. Nothing about the screen would look wrong.
              .addressedBy(Set.of("version:integer")),
          // The status change. LOGISTICIAN + per-order scope, so the app offers it only to a
          // Logistician and names the refusal when the order is outside their slice.
          //
          // `status` and `version` are both REQUIRED on the request: dropping either from the
          // required list would be a widening the app survives, but ADDING a third required field
          // is what a shipped build cannot send -- which is what this half of the entry guards.
          new ContractOperation(
              "/api/v1/orders/{id}/status",
              "put",
              Set.of("id", "status", "version"),
              Set.of("status", "version")),
          // Phase 2, the org bank a member may see. `/org-units/bank/**`, never
          // `/bank/accounts/**`: the latter is the bank-employee surface and lists every account.
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
                  // The request sheet is built out of these three. `canRequest` decides which
                  // accounts a withdrawal or transfer may name at all; `approvalLimit` is the
                  // threshold the sheet states live under the amount, per caller and per account;
                  // `approvalExempt` is why it sometimes states nothing. Losing any of them turns
                  // a form that explains itself into one that guesses.
                  "canRequest",
                  "approvalLimit",
                  "approvalExempt")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}",
              "get",
              Set.of(
                  "detail", "account", "delta30d", "bookingCount", "name", "accountNo", "balance")),
          // Phase 3, the only bank writes a member has: the settings of an account they are
          // responsible for. Deliberately NOT `/api/v1/bank/**` — deposits, withdrawals and
          // transfers are all `hasRole(BANK_EMPLOYEE)` and belong to the bank-employee surface the
          // app does not carry (REQ-APP-BANK-001).
          //
          // `canSetTarget` and `canConfigureVisibility` are the two fields that make this slice
          // work at all: the server states what the caller may do, so the app offers exactly that
          // and guesses at no role. Losing either would leave the app either hiding a control a
          // holder is entitled to, or offering one that answers 403.
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/settings",
              "get",
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
                  "allMembersGranted")),
          // The target is version-echoed and the target itself is optional: clearing it is sending
          // no target at all, which is why only `version` is required.
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/balance-target",
              "put",
              Set.of("accountId", "balanceTarget", "version", "canSetTarget"),
              Set.of("version")),
          // Visibility is addressed entirely by its path — a role bucket by code, the all-members
          // switch by a boolean path segment — so neither carries a body to freeze.
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/visibility/role/{roleCode}",
              "post",
              Set.of("accountId", "grantedRoleCodes", "version")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/visibility/role/{roleCode}",
              "delete",
              Set.of("accountId", "grantedRoleCodes", "version")),
          new ContractOperation(
              "/api/v1/org-units/bank/accounts/{id}/visibility/all-members/{enabled}",
              "put",
              Set.of("accountId", "allMembersGranted", "version")),
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
                      "holderHandle"))
              .addressedBy(Set.of("page:integer", "size:integer")),
          // Phase 5, the member's booking requests (app REQ-APP-BANK-008). Still
          // `/org-units/bank/**`: confirming and rejecting live on `/api/v1/bank/requests/**`,
          // which is `BANK_EMPLOYEE` and stays off this list.
          //
          // `requiredApprover` is the field that keeps the app honest about the approval model.
          // There is no count of approvals anywhere — one owner approval, granted by the class
          // this field names (REQ-BANK-041/-047) — and the app's row chip renders that class.
          // Dropping it would leave the client unable to say who a request is waiting on.
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
                  "status",
                  "requesterHandle",
                  "requiresOwnerApproval",
                  "requiredApprover",
                  "ownerApprovalGranted",
                  "ownerApprovalGrantedByHandle",
                  "createdAt",
                  "version")),
          // A transfer's destinations. The app shows the name and falls back to the number, so
          // both are relied on.
          new ContractOperation(
              "/api/v1/org-units/bank/transfer-targets", "get", Set.of("id", "name", "accountNo")),
          // Raising one. `targetAccountId` travels only for a TRANSFER; the server ignores it
          // otherwise, and the app omits it rather than putting a value on the wire that
          // describes nothing.
          new ContractOperation(
              "/api/v1/org-units/bank/requests",
              "post",
              Set.of("id", "status", "requiresOwnerApproval", "requiredApprover", "version"),
              Set.of("sourceAccountId", "type", "amount")),
          // Correcting one's own. The account and the kind are absent on purpose: the server
          // refuses a change to either, so a client that sent them would be asking for a 400.
          // Only `amount` is required. The version travels too and the app sends it, but the
          // server accepts the edit without one — recorded as it is rather than as it ought to be,
          // because this set is what a shipped build is held to.
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
          // The two approval verbs take NO body — no version to echo, and the app sends none.
          // Frozen so that gaining one would be a contract change rather than a silent 400.
          new ContractOperation(
              "/api/v1/org-units/bank/requests/{id}/owner-approval",
              "post",
              Set.of("id", "ownerApprovalGranted", "ownerApprovalGrantedByHandle", "version")),
          new ContractOperation(
              "/api/v1/org-units/bank/requests/{id}/owner-approval",
              "delete",
              Set.of("id", "ownerApprovalGranted", "version")),
          // Phase 2, the member's own hangar. The row's `shipType` and `location` are nested
          // objects whose `name` is what the card actually shows, which is why the guard now
          // descends into a referenced schema and not only into an array's items.
          //
          // `owner` is deliberately NOT frozen. It is a full user record -- email, roles, rank --
          // and on this endpoint it is always the caller's own, so the app has no reason to read
          // it. Freezing it would oblige the backend to keep sending a payload nobody wants.
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
                      // Added in phase 3: the app now edits these rows, and the edit echoes the
                      // version it read. A read-only client had no use for it; a writing one cannot
                      // save without it.
                      "version"))
              .addressedBy(Set.of("search:string", "page:integer", "size:integer")),
          // The org-unit half of the same screen: one row per ship type with its counts.
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
              // The org half of the Hangar screen, addressed exactly like `my-ships` beside it.
              .addressedBy(Set.of("search:string", "page:integer", "size:integer")),
          // Phase 2, the dashboard's announcement band. `content` is the whole point of the
          // operation, and the endpoint answers 204 when there is nothing to announce -- a
          // no-content answer the client must read as "no banner", never as a failure. That
          // distinction lives in the client (`ApiReader.getOptional`), because a schema cannot
          // express "and sometimes there is no body".
          new ContractOperation("/api/v1/announcement", "get", Set.of("content", "updatedAt")),
          // Phase 2, the notification inbox. `params` is frozen as a field but its CONTENT is
          // not a contract this guard can hold: the app renders each notification from
          // `notifications.type.<TYPE>` with those named placeholders substituted, so a renamed
          // placeholder changes a sentence the server never sees. The client's answer is to fall
          // back to the generic wording when a placeholder cannot be filled -- a defence that
          // belongs there, because no schema check can express it.
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
          // The push channel. Its response is a stream, not a schema, so the field assertion here
          // is vacuous by nature -- what this entry is worth is the OTHER guard: the path and verb
          // must keep existing. The event NAMES (`connected`, `notification`, `heartbeat`,
          // `replaced`) are the real contract and are pinned in the app's spec, since nothing in
          // this document describes them.
          new ContractOperation("/api/v1/notifications/stream", "get", Set.of()),
          // Phase 5, the inbox's mutating half. The app shipped a read-only inbox because these
          // four were "Phase 3" work that phase 3 never picked up; design chapter 07 specifies a
          // fully interactive one, so the deferral was a defect rather than a decision.
          //
          // Only the DELETE of a single row is a 204. The other three answer with a body, and
          // `unreadCount` on the two bulk results is the field worth freezing hardest: it is what
          // lets the badge settle from the same response that changed it. Without it the app would
          // have to follow every bulk action with a second call to `/unread-count`, and the badge
          // would disagree with the list for as long as that took.
          //
          // `/read` and `/read-all` are literal segments sitting beside `{id}`, which is a UUID --
          // they cannot collide, and the vhost allow-list relies on exactly that to admit the
          // three by name without admitting the family or the `/notification-rules` admin surface
          // next to it.
          new ContractOperation("/api/v1/notifications/{id}/read", "post", Set.of("id", "read")),
          new ContractOperation(
              "/api/v1/notifications/read-all", "post", Set.of("affected", "unreadCount")),
          new ContractOperation("/api/v1/notifications/{id}", "delete", Set.of()),
          new ContractOperation(
              "/api/v1/notifications/read", "delete", Set.of("affected", "unreadCount")),
          // Phase 2, the caller's own record. The app needs two fields of it. Its own backend user
          // id: an Operation's payout rows are keyed by that id -- not by the Keycloak `sub` the
          // app holds, and not by a name -- so "Dein Anteil" cannot be found without it, and
          // neither can "assign me to this order". And `isLogistician`, which decides whether the
          // Auftrag detail offers the status control at all; without it the app would either hide
          // a control a Logistician is entitled to or offer one that answers 403.
          //
          // Still not the roles or the permissions set: the app asks one yes/no question and this
          // is the field that answers it. Freezing the rest would buy the backend a constraint on
          // a payload nobody reads.
          new ContractOperation(
              "/api/v1/users/me", "get", Set.of("id", "isLogistician", "isMissionManager")),
          // Phase 2, the Operationen segment of the same screen. The row is deliberately thin:
          // OperationDto carries no mission or participant count, and the owner decided against
          // adding them rather than spend aggregate queries on a list that has documented itself
          // as cheap ("the bulk endpoints have no reason to spend the extra count query").
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
          // The Operation detail. `payoutPreliminary` is frozen because it is authoritative HERE
          // and nowhere else -- the app reads it to say that the payout figures may still
          // rebalance, and a screen that silently stopped saying so would present a provisional
          // number as final.
          new ContractOperation(
              "/api/v1/operations/{id}",
              "get",
              Set.of("id", "name", "description", "status", "payoutPreliminary")),
          // The Finanz-Rollup. `truncated` is frozen for the same reason ADR-0104 exists: it is
          // the field that tells the member the per-mission list is not all of it, and losing it
          // turns a capped list into one that looks complete.
          new ContractOperation(
              "/api/v1/operations/{id}/finance-summary",
              "get",
              Set.of(
                  "operationId", "totalSum", "missions", "truncated", "missionId", "missionName")),
          // The Auszahlungen tab. Read-only in this phase: the app renders each participant's
          // share and whether it has been paid, and the manager toggle behind it is phase 3.
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
          // Phase 3, "Mein Inventar" — the first WRITES in the contract set, and the reason the
          // request-side guard above exists. A member's personal stock is theirs alone: the list
          // is me-scoped by the service, so no id of anyone else appears in these paths.
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
          // `locationName` is frozen although the create/update pair does not send it: it is
          // resolved server-side from the UEX id, and it is the only human-readable form of the
          // place the member picked. Without it a row can only show a number.
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
          // `version` is required on the update and on nothing else. It is the optimistic lock:
          // the client echoes what it read, and a concurrent edit answers 409 instead of
          // overwriting. A future field added to this body must be optional, or every build in
          // the field starts failing its saves.
          new ContractOperation(
              "/api/v1/personal-inventory/{id}",
              "put",
              Set.of("id", "name", "quantity", "locationUexId", "locationType", "version"),
              Set.of("name", "quantity", "locationUexId", "locationType", "version")),
          // The delete answers 204 with no body, so there is nothing to freeze but the path and
          // the verb — which is exactly what an old build needs to keep working.
          new ContractOperation("/api/v1/personal-inventory/{id}", "delete", Set.of()),
          // The location picker behind the editor. Cities and space stations in one search, keyed
          // by the UEX id the two write bodies send. `type` is frozen because it IS the
          // `locationType` half of that pair — the row carries both halves of what gets saved.
          new ContractOperation(
                  "/api/v1/uex/locations/search",
                  "get",
                  Set.of("uexId", "type", "name", "starSystemName", "parentName"))
              .addressedBy(Set.of("q:string", "limit:integer")),
          // Phase 3, the Blueprints half of the same screen. `removable` is frozen because it
          // qualifies the row rather than describing it: an entry the server will not let go of
          // must not be offered a delete action that answers 409 (same class as `redacted` and
          // `truncated`).
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
          // Only `version` is required on the update: the note and the date are both optional, and
          // an app that sends one without the other must keep working.
          new ContractOperation(
              "/api/v1/personal-blueprints/{id}",
              "put",
              Set.of("id", "productKey", "productName", "note", "acquiredAt", "version"),
              Set.of("version")),
          new ContractOperation("/api/v1/personal-blueprints/{id}", "delete", Set.of()),
          // Phase 5, the recipe behind one owned blueprint. Design ch. 09 lays the tablet's
          // Blueprints out as master-detail "with live ingredient quality", and the quality it
          // means is `minQuality` on each ingredient -- the lowest grade that still satisfies the
          // requirement. Without this operation the detail pane of that layout has nothing to show.
          //
          // Both quantity fields are frozen, and deliberately both: `quantityScu` and
          // `quantityUnits` are the same amount in two scales, and the app has already been bitten
          // once by reading a unit figure as SCU (the refinery's 100x stock bug). Freezing the pair
          // means a client can render the one its column is labelled for instead of converting.
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
          // The craftability chip. `limitingMaterialName` is what turns "N Materialien fehlen"
          // into a sentence a member can act on, and `craftableWithRefinery` is the second answer
          // the same question has once refining is allowed for — dropping either would leave the
          // chip stating a bare boolean.
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
          // The product picker behind "Blueprint hinzufügen". `ownedByCurrentUser` is frozen
          // because it is what keeps the picker from offering a duplicate the server would then
          // refuse.
          new ContractOperation(
                  "/api/v1/blueprints/products/search",
                  "get",
                  Set.of("productKey", "name", "manufacturerName", "ownedByCurrentUser"))
              .addressedBy(Set.of("q:string", "limit:integer")),
          // Phase 3, the Hangar's own ships. The write path is /hangar/ships, NOT
          // /hangar/users/{id}/ships: the second one names a member and is the admin surface,
          // which this contract set has no reason to carry.
          //
          // The request requires `insurance` and `shipTypeId` and nothing else. `version` is
          // deliberately NOT required by the schema — a create has none — but the app sends it on
          // every update, and freezing the required list as it stands is what stops the server
          // from making a field mandatory that a shipped build does not send.
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
          // The two pickers the editor needs. `manufacturer` is frozen on the ship type because it
          // is what tells two similarly named hulls apart in a list of hundreds.
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
          // Phase 3, the Lager's three bookings. Every one of them carries `version`, and the two
          // that move stock carry `amount` — the pair that decides what actually happens to a
          // member's material, which is why they are the required fields the contract freezes.
          // The entry level of the Lager tree, added in phase 3: a member cannot book out what they
          // cannot select, and the two levels phase 2 read stop at the stack. `version` is frozen
          // here for the same reason as on my-ships — every booking echoes it.
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
              // The stack drill-down: five of these together name ONE stack. Dropping any of them
              // does
              // not widen the answer, it asks a different question.
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
          // The book-out's `type` is DISCARD / TRANSFER / SELL. The schema does not mark it
          // required — a book-out without one defaults server-side — so the required-enum guard
          // leaves it alone by design, and the wording is pinned in the app's spec instead.
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
          // The four pickers the booking form needs. `quantityType` is frozen on the material
          // because it is the unit every amount on the screen is expressed in — SCU or units — and
          // a number without its unit is not a quantity.
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
          // `effectiveName` and not `username`: it is what the web app renders and what the member
          // recognises. The rest of the record — email, roles, permissions — is deliberately not
          // frozen, because the picker must not read it.
          new ContractOperation(
                  "/api/v1/users/search",
                  "get",
                  Set.of("content", "page", "totalElements", "totalPages", "id", "effectiveName"))
              .addressedBy(Set.of("query:string", "page:integer", "size:integer")),
          new ContractOperation(
              "/api/v1/materials/{id}/terminals",
              "get",
              Set.of("terminalId", "terminalName", "priceSell")),
          // Phase 4, the live-sync bridge (ADR-0143). Like the notification stream above, the
          // response is a stream rather than a schema, so the field assertion is vacuous and the
          // path-and-verb guard is the whole point. The event NAMES (`subscribed`, `changed`,
          // `heartbeat`) and the frame shape are the real contract; they are pinned in the app's
          // REQ-APP-SYNC spec and in LiveSyncStreamServiceTest, since nothing in this document
          // describes them.
          //
          // The TOPIC vocabulary is a contract too, and a nastier one: renaming a room or a section
          // key breaks a shipped client silently -- it keeps streaming and simply never hears about
          // that screen again. That half cannot live here, because the topics appear in no OpenAPI
          // schema; it is held by LiveSyncTopicRegistryParityTest against the frontend's registry.
          new ContractOperation("/api/v1/live-sync/stream", "get", Set.of())
              // The live-sync stream's whole subscription protocol is this one parameter
              // (ADR-0143).
              // Losing it would not degrade the stream, it would silently open every client on
              // nothing.
              .addressedBy(Set.of("topics:string")),
          // The publish half. Frozen for its request fields rather than its response: it answers
          // 202 with no body, and what a shipped client must keep being able to SEND is the frame.
          new ContractOperation(
              "/api/v1/live-sync/changed", "post", Set.of(), Set.of("topic", "sections")),
          // Phase 4, Beförderung. The member's own record, me-scoped by construction: neither path
          // takes an id, which is why neither appears in the query-parameter freeze.
          //
          // `hasConfiguredRules` is frozen for a reason that is easy to lose: it is what tells "no
          // rules exist for this step" apart from "you do not meet them". Drop it and a shipped app
          // renders an empty requirement list, which reads as a verdict the organisation never
          // made. `assignedLevel` is frozen as a FIELD but deliberately not as a required enum --
          // the levels are configured per organisation, the app shows the server's own spelling,
          // and freezing the constants would bind a vocabulary that is theirs to change.
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
          // Phase 4, the forced-update gate. This one is in the set for an inverted reason: every
          // other entry is frozen so a shipped app keeps working, and this is frozen so a shipped
          // app can be told to STOP working. If the server ever renamed `minimumVersionCode`, the
          // build that most needs the answer -- the one already too old -- would read no floor and
          // carry on against a contract that no longer exists. It is also the operation an app
          // calls before it has a token, so it must keep answering when everything else refuses.
          new ContractOperation(
              "/api/v1/app/version-policy",
              "get",
              Set.of("minimumVersionCode", "latestVersionCode", "releasesUrl")),
          // Phase 4, Raffinerie. `status` is frozen as a FIELD and its constants are frozen by the
          // required-enum guard, because the app sends them back as query values -- a renamed
          // constant turns the whole list request into a 400 while the screen keeps loading.
          //
          // `endsAt` is frozen on the LIST only, and that asymmetry is deliberate: the detail DTO
          // does not carry it, and the app computes it from `startedAt` + `durationMinutes` so the
          // two screens agree. Freezing it on the detail would record a field that has never been
          // sent.
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
              // `status` repeats, which the array type records: the two live filters are one
              // request for
              // OPEN + IN_PROGRESS, split on the device.
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
          // The booking. Its item list is what the whole slice turns on: the endpoint marks an
          // order COMPLETED whatever that list contains, so a renamed field would mark orders
          // stored while creating nothing -- silently, and unrecoverably for the member.
          new ContractOperation(
              "/api/v1/refinery-orders/{id}/store", "post", Set.of(), Set.of("items")),
          // Phase 4, Materialbörse. `kind` is frozen with its constants because the app reads a
          // DIFFERENT PAIR OF FIELDS depending on it: an item names itself in `itemName` /
          // `itemQuantity`, a material in `material` / `amount`. A renamed constant renders every
          // item row blank rather than failing.
          //
          // `quantityType` is frozen for the reason the unit exists: an item counted in pieces and
          // labelled „SCU" is a quantity a member acts on in a handover the tool never sees.
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
          // The pledge and its withdrawal answer with the updated row, which is what lets the app
          // replace one entry instead of re-reading the page. Freezing the response is therefore
          // not optional here: a body that stopped carrying `interestCount` would leave the count
          // frozen on screen with no error anywhere.
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
          // The two creates. `inventoryItemId` is what makes an offer an offer of something the
          // member actually holds; `materialId` the same for a request.
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
          // The offer sheet's stock suggestion. `alreadyReleased` is frozen because it is the only
          // thing stopping a member from offering the same stack twice, and `quantityType` for the
          // unit reason above.
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
              // The offer sheet's picker: `q` is its search box and `kind` its Material/Item radio
              // (REQ-MARKET-002). `kind` carries the MATERIAL / ITEM vocabulary already frozen on
              // the
              // offers response -- but as a PARAMETER enum, which the required-enum guard does not
              // walk,
              // so the type is all this half can hold and the constants stay pinned by that
              // response.
              .addressedBy(Set.of("q:string", "kind:string")));

  /**
   * Contract operations the app addresses by <strong>no</strong> query parameter, although the
   * document declares one.
   *
   * <p>An exemption ledger, not a second freeze list. The coverage guard below refuses an operation
   * that takes query parameters and records none, because that silence is indistinguishable from
   * forgetting — which is exactly how five of them slipped in. Naming one here is the way to say
   * "considered, and the app sends nothing", and it costs a line in a diff a reviewer sees.
   *
   * <p>{@code GET /api/v1/users/me/memberships} declares {@code allKinds}, and the app relies on
   * its <em>default</em> rather than sending it: {@code false} is the Staffel/SK-only shape the
   * org-unit switcher renders, and the {@code kind} field frozen on its response is that pair. The
   * house rule — freeze only what the app sends — keeps it out, for the same reason {@code sort} is
   * absent from the paged lists whose server-side default order the app takes as it comes.
   */
  private static final Set<String> ADDRESSED_BY_NO_QUERY_PARAMETER =
      Set.of("get /api/v1/users/me/memberships");

  @Test
  @DisplayName("the query parameters a shipped client asks with still exist, with their types")
  void theContractQueryParametersAreFrozen() throws IOException {
    // A response field that disappears is caught by the field guard and a request field that
    // becomes mandatory by the required-field one. A query parameter was caught by neither, and it
    // is how the app says WHICH rows it wants: rename `materialId`, retype `quality` from integer
    // to string, and the installed build asks a question the server no longer understands. Two of
    // those happened inside one afternoon on the Lager slice -- a 400 TYPE_MISMATCH on a decimal
    // quality, and an omitted `owningOrgUnitId` that the server reads as "the unpooled stack"
    // rather than "any pool" -- and neither would have failed a build.
    //
    // Frozen as `name:type` so a retype is caught as loudly as a rename, and asserted as a subset
    // so adding an optional parameter stays free. Paging counts: a shipped list that cannot ask
    // for page two is as broken as one that cannot parse a row.
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
    // The guard that keeps the guard above honest, and the one this file was missing: freezing the
    // parameters once closed nothing durably, because the next operation added could simply not
    // mention them. Five already had: a paged Finanzen tab, the paged Hangar overview, the offer
    // sheet's picker, the assignee note's DELETE -- whose `version` IS the optimistic lock -- and
    // the org-unit switcher, whose answer turned out to be "none", which is why it is the one
    // entry in the ledger above rather than a fifth freeze.
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
   * The query parameters an operation declares, as {@code name:type}.
   *
   * @param document the parsed API document
   * @param operation the contract operation to resolve
   * @return the declared query parameters; an array's element type is not part of the key, since a
   *     client sends the same repeated parameter either way
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
   * Enum constants a shipped client cannot survive a change to, keyed {@code Schema.property}.
   *
   * <p><strong>Only REQUIRED enum properties are here, and that is the whole point.</strong> The
   * Android client parses with kotlinx.serialization and {@code coerceInputValues}, which turns an
   * unrecognised constant into {@code null} — but only where the property is nullable. A required
   * one has nowhere to go, so an unknown value fails the **entire response**, not the field.
   *
   * <p>Measured on the app before this guard existed: a single unknown {@code JobTypeDto.archetype}
   * made the whole mission-detail response unparseable. The list endpoint has no nested enums and
   * kept working, so the member would have seen a list whose every row failed to open — on an APK
   * in the field that cannot be redeployed. The client cannot defend itself either:
   * openapi-generator's {@code enumUnknownDefaultCase} is a no-op for kotlinx_serialization, and
   * the app does not even read this field. It is required purely to parse.
   *
   * <p>So the defence has to be here, and it is a release-ordering one: adding a constant fails
   * this build, which forces the app to ship a build that knows it <em>before</em> the server
   * starts sending it.
   *
   * <p>Nullable enums are deliberately absent. They degrade to {@code null} — an objective loses
   * its kind badge, not its screen — and freezing them would make this fire on harmless additions,
   * which is how a guard gets widened until it means nothing.
   *
   * <p><strong>Requests count as well as responses.</strong> A shipped build sends {@code
   * type=TRANSFER} and {@code status=IN_PROGRESS} as literal strings; renaming a constant
   * server-side turns every one of those writes into a 400 that the member reads as "the app is
   * broken". The failure is quieter than the response one — the screen still loads — and it is just
   * as unfixable without a new APK, so the same release ordering applies: ship a build that sends
   * the new constant first.
   */
  private static final Map<String, Set<String>> FROZEN_REQUIRED_ENUMS =
      Map.of(
          "JobTypeDto.archetype",
          Set.of("CREW", "MISSION"),
          // Both halves of the personal-inventory editor send this one, and it was invisible until
          // the guard started walking requests: a renamed constant would have turned every save on
          // an installed build into a 400 while the screen kept loading.
          "PersonalInventoryItemCreateRequest.locationType",
          Set.of("CITY", "SPACE_STATION"),
          "PersonalInventoryItemUpdateRequest.locationType",
          Set.of("CITY", "SPACE_STATION"),
          "UpdateJobOrderStatusDto.status",
          Set.of("OPEN", "IN_PROGRESS", "REJECTED", "COMPLETED"),
          "UpdatePayoutPreferenceRequest.preference",
          Set.of("PAYOUT", "DONATE"),
          "MissionFinanceEntryCreateDto.type",
          Set.of("INCOME", "EXPENSE"),
          "MissionFinanceEntryUpdateDto.type",
          Set.of("INCOME", "EXPENSE"),
          // The three movements the app's request sheet sends as literals. Renaming one would
          // turn every booking request an installed build raises into a 400 while the sheet still
          // opens and still looks fine.
          "CreateBankBookingRequest.type",
          Set.of("DEPOSIT", "WITHDRAWAL", "TRANSFER"));

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
   * Collects every required enum property reachable from the contract set's schemas.
   *
   * <p>Walks the schema graph transitively, because a client parses the whole payload and not just
   * the fields it reads: an enum four levels down inside a participant's job type is as fatal as
   * one on the root object. Array properties are followed through their {@code items}, since the
   * item's own {@code required} list is what decides whether an element can be parsed at all.
   *
   * <p>Request bodies are walked alongside responses. The direction of the break differs — a
   * response enum fails the parse, a request enum fails the write with a 400 — but both are
   * unfixable on an installed build.
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
      if ("array".equals(value.path("type").asString(""))) {
        walkSchema(schemas, schemaName(value.path("items")), visited, found);
        continue;
      }
      String target = schemaName(value);
      JsonNode enumNode = target != null ? schemas.path(target).get("enum") : value.get("enum");
      if (enumNode != null && required.contains(property.getKey())) {
        Set<String> constants = new TreeSet<>();
        enumNode.forEach(entry -> constants.add(entry.asString()));
        found.put(name + "." + property.getKey(), constants);
      }
      walkSchema(schemas, target, visited, found);
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
    // The mirror image of the response guard, and the half that only matters from phase 3 on: a
    // *new* required request field is a 400 for every build already in the field, which sends the
    // payload it was written against. Removing one is safe (the old build keeps sending it), so
    // this asserts equality in one direction only — nothing added.
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
   * Reads the {@code required} list of an operation's request body schema.
   *
   * <p>Follows the {@code $ref} of the first media type declared, which is how springdoc emits a
   * single-body operation. An operation with no request body answers an empty set, so a read entry
   * needs no special case.
   *
   * @param document the parsed API document
   * @param operation the contract operation to resolve
   * @return the required property names, empty when there is no body or nothing is required
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
   * <p>It additionally descends into the item schema of <strong>every array property</strong>, so
   * the recorded set spans the envelope and the rows it carries. That covers a paged response's
   * {@code content} — stopping at the envelope would freeze {@code totalElements} and leave every
   * field a member actually reads unguarded — and equally an embedded list such as an operation's
   * {@code payouts}, whose rows are parsed one by one exactly like a page's are.
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
          properties.addAll(nestedProperties(document, schemaProperties));
        }
      }
    }
    return properties;
  }

  /**
   * Collects the property names of the schemas a response's properties reference.
   *
   * <p>Descends **two levels** into the schemas a response references — an array's items and a
   * plain nested object alike. Two, because a paged response spends the first on its own rows: the
   * envelope references the row, and the row references the object whose field the screen shows. A
   * {@code PageResponse} carries its rows under {@code content}, a roll-up carries its
   * per-participant rows under a named list, and a ship carries its {@code shipType} as an object
   * whose {@code name} is the whole point of the row. All three are invisible to a resolver that
   * stops at the top-level object: an entry that froze only {@code payouts} would freeze the *list*
   * and nothing in it, and a renamed {@code shareAmount} would reach a device with this guard
   * green.
   *
   * <p>Two levels, not the whole graph. The deeper the walk, the more a recorded name could be
   * satisfied by an unrelated schema somewhere far from the field it was recorded for, and the
   * guard would read as stronger than it is.
   *
   * <p>The names land in one flat set together with the envelope's, which is the shape this guard
   * has always had. That makes a recorded field satisfiable by a same-named field on another schema
   * in the same response — accepted, because the alternative is a per-schema contract record and
   * the failure it would add precision to (two schemas in one response sharing a field name where
   * only one of them keeps it) is not the break this guard exists for.
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
