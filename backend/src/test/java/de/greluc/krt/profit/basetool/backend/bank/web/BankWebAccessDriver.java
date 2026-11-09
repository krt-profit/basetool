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

package de.greluc.krt.profit.basetool.backend.bank.web;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import de.greluc.krt.profit.basetool.backend.bank.internal.BankAccountRepository;
import de.greluc.krt.profit.basetool.backend.bank.internal.BankBookingRequestRepository;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Band;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Case;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Holder;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Request;
import de.greluc.krt.profit.basetool.backend.scope.api.CustomJwtGrantedAuthoritiesConverter;
import de.greluc.krt.profit.basetool.backend.scope.api.RequestScopeResolver;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Runs matrix cases through the web endpoints with MockMvc and renders each outcome as one line.
 *
 * <p>An outcome is the HTTP status, followed by the problem {@code code} of a refusal or by the
 * access-relevant fields of a success: the routing of a raised request, the capability flags of a
 * detail, the seeded subjects of a listing. The caller is authenticated by a bearer token whose
 * authorities the production converter assembles from {@code user_roles} and the memberships.
 */
final class BankWebAccessDriver {

  private static final String ORG = "/api/v1/bank/org-units";
  private static final String NO_FLAGS = "-";

  private final MockMvc mockMvc;
  private final CustomJwtGrantedAuthoritiesConverter authorities;
  private final EntityManager entityManager;
  private final BankAccountRepository accountRepository;
  private final BankBookingRequestRepository requestRepository;
  private final JsonMapper json = JsonMapper.builder().build();
  private final BankAccessWorld world;

  /**
   * Binds the driver to one seeded world.
   *
   * @param context the running web application context
   * @param world the world the cases run against
   */
  BankWebAccessDriver(@NotNull WebApplicationContext context, @NotNull BankAccessWorld world) {
    this.mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    this.authorities = context.getBean(CustomJwtGrantedAuthoritiesConverter.class);
    this.entityManager = context.getBean(EntityManager.class);
    this.accountRepository = context.getBean(BankAccountRepository.class);
    this.requestRepository = context.getBean(BankBookingRequestRepository.class);
    this.world = world;
  }

  /**
   * Runs one case and renders its outcome.
   *
   * @param c the case
   * @return the outcome line
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  String run(@NotNull Case c) throws Exception {
    entityManager.flush();
    String outcome = dispatch(c);
    entityManager.flush();
    return outcome;
  }

  /**
   * Sends the case's call or calls.
   *
   * @param c the case
   * @return the outcome line
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String dispatch(@NotNull Case c) throws Exception {
    BigDecimal amount = c.band().getAmount();
    return switch (c.operation()) {
      case ORG_BALANCES ->
          render(send(c, get(ORG + "/balances")), body -> accountList(body, this::balanceFlags));
      case ORG_VISIBLE ->
          render(
              send(c, get(ORG + "/accounts/visible").param("size", "1000")),
              body -> accountList(body.path("content"), _ -> NO_FLAGS));
      case ORG_VIEW -> render(send(c, get(ORG + "/accounts/" + accountId(c))), this::detailFlags);
      case ORG_SETTINGS ->
          render(
              send(c, get(ORG + "/accounts/" + accountId(c) + "/settings")), this::settingsFlags);
      case STAFF_LIST ->
          render(
              send(
                  c,
                  get("/api/v1/bank/accounts")
                      .param("size", "1000")
                      .param("sort", "accountNo,desc")),
              body -> accountList(body.path("content"), _ -> NO_FLAGS));
      case STAFF_VIEW ->
          render(
              send(c, get("/api/v1/bank/accounts/" + accountId(c))),
              body -> flags(body.path("capabilities"), "canDeposit", "canWithdraw", "canTransfer"));
      case HOLDER_VIEW ->
          render(
              send(c, get("/api/v1/bank/holders/" + world.holder((Holder) c.subject()))), _ -> "");
      case FOREIGN_REQUESTS ->
          render(send(c, get(ORG + "/requests/foreign")), body -> requestList(body, false));
      case STAFF_QUEUE ->
          render(
              send(c, get("/api/v1/bank/requests").param("size", "1000")),
              body -> requestList(body.path("content"), true));
      case RAISE_DEPOSIT -> raise(c, "DEPOSIT", amount);
      case RAISE_WITHDRAWAL -> raise(c, "WITHDRAWAL", amount);
      case RAISE_TRANSFER -> raise(c, "TRANSFER", amount);
      case CORRECT_OWN -> correct(c, amount);
      case CANCEL_OWN ->
          render(
              send(
                  c,
                  post(ORG + "/requests/" + requestId(c) + "/cancel")
                      .content(body(Map.of("version", requestVersion(c))))),
              _ -> "");
      case CONFIRM -> confirm(c, world.request((Request) c.subject()));
      case REJECT ->
          render(
              send(
                  c,
                  post("/api/v1/bank/requests/" + requestId(c) + "/reject")
                      .content(body(Map.of("reason", "Matrix", "version", requestVersion(c))))),
              _ -> "");
      case APPROVE -> approve(c, world.request((Request) c.subject()));
      case FOUR_EYES_CONFIRM -> fourEyes(c, Account.OWN_ORG_UNIT, "DEPOSIT", true);
      case FOUR_EYES_APPROVE -> fourEyes(c, Account.CARTEL, "WITHDRAWAL", false);
      case SET_VISIBILITY ->
          render(
              send(
                  c,
                  post(ORG + "/accounts/" + accountId(c) + "/visibility/user/" + world.outsider())),
              _ -> "");
      case SET_TARGET ->
          render(
              send(
                  c,
                  put(ORG + "/accounts/" + accountId(c) + "/balance-target")
                      .content(body(Map.of("target", 5000, "version", accountVersion(c))))),
              _ -> "");
      case SET_LIMIT ->
          render(
              send(
                  c,
                  put(ORG
                          + "/accounts/"
                          + accountId(c)
                          + "/approval-limit/user/"
                          + world.outsider())
                      .content(body(Map.of("limit", 2000)))),
              _ -> "");
      case STAFF_SET_TARGET ->
          render(
              send(
                  c,
                  put("/api/v1/bank/accounts/" + accountId(c) + "/balance-target")
                      .content(body(Map.of("target", 5000, "version", accountVersion(c))))),
              _ -> "");
      case STAFF_SET_TIERS ->
          render(
              send(
                  c,
                  patch("/api/v1/bank/accounts/" + accountId(c) + "/approval-tiers")
                      .content(
                          body(
                              Map.of(
                                  "employeeCeiling", BankAccessSeeder.CARTEL_T1,
                                  "areaLeadCeiling", BankAccessSeeder.CARTEL_T2,
                                  "version", accountVersion(c))))),
              _ -> "");
      case DIRECT_DEPOSIT ->
          render(
              send(
                  c,
                  post("/api/v1/bank/deposits")
                      .content(
                          body(
                              Map.of(
                                  "accountId", accountId(c),
                                  "holderId", vault(),
                                  "amount", amount)))),
              _ -> "");
      case DIRECT_WITHDRAWAL ->
          render(
              send(
                  c,
                  post("/api/v1/bank/withdrawals")
                      .content(
                          body(
                              Map.of(
                                  "accountId",
                                  accountId(c),
                                  "holderId",
                                  vault(),
                                  "amount",
                                  amount,
                                  "justification",
                                  BankAccessSeeder.JUSTIFICATION)))),
              this::directOutcome);
      case DIRECT_TRANSFER ->
          directTransfer(c, world.transferTargetFor((Account) c.subject()), amount);
      case DIRECT_TRANSFER_TO_HIDDEN ->
          directTransfer(c, world.hiddenTransferTargetFor((Account) c.subject()), amount);
    };
  }

  /**
   * Books a direct transfer from the case's account.
   *
   * @param c the case
   * @param destination the destination account
   * @param amount the amount
   * @return the outcome line
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String directTransfer(
      @NotNull Case c, @NotNull UUID destination, @NotNull BigDecimal amount) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sourceAccountId", accountId(c));
    payload.put("sourceHolderId", vault());
    payload.put("destinationAccountId", destination);
    payload.put("destinationHolderId", vault());
    payload.put("amount", amount);
    payload.put("justification", BankAccessSeeder.JUSTIFICATION);
    return render(
        send(c, post("/api/v1/bank/transfers").content(body(payload))), this::directOutcome);
  }

  /**
   * Raises a booking request on the case's account.
   *
   * @param c the case
   * @param type the request type
   * @param amount the amount
   * @return the outcome line
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String raise(@NotNull Case c, @NotNull String type, @NotNull BigDecimal amount)
      throws Exception {
    return render(send(c, raiseCall((Account) c.subject(), type, amount)), this::routing);
  }

  /**
   * Builds the raise call.
   *
   * @param source the source account
   * @param type the request type
   * @param amount the amount
   * @return the request builder
   */
  @NotNull
  private MockHttpServletRequestBuilder raiseCall(
      @NotNull Account source, @NotNull String type, @NotNull BigDecimal amount) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("sourceAccountId", world.account(source));
    payload.put("type", type);
    payload.put("amount", amount);
    if (!"DEPOSIT".equals(type)) {
      payload.put("justification", BankAccessSeeder.JUSTIFICATION);
    }
    if ("TRANSFER".equals(type)) {
      payload.put("targetAccountId", world.transferTargetFor(source));
    }
    return post(ORG + "/requests").content(body(payload));
  }

  /**
   * Corrects the member's own request to the case's amount.
   *
   * @param c the case
   * @param amount the corrected amount
   * @return the outcome line
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String correct(@NotNull Case c, @NotNull BigDecimal amount) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("amount", amount);
    payload.put("justification", BankAccessSeeder.JUSTIFICATION);
    payload.put("version", requestVersion(c));
    return render(
        send(c, put(ORG + "/requests/" + requestId(c)).content(body(payload))), this::routing);
  }

  /**
   * Confirms a request with the vault holder and without an over-limit attestation.
   *
   * @param c the case, whose actor confirms
   * @param requestId the request to confirm
   * @return the outcome line
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String confirm(@NotNull Case c, @NotNull UUID requestId) throws Exception {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("holderId", vault());
    payload.put("destinationHolderId", vault());
    payload.put("ownerApprovalConfirmed", false);
    payload.put("version", versionOf(requestId));
    return render(
        send(c, post("/api/v1/bank/requests/" + requestId + "/confirm").content(body(payload))),
        _ -> "");
  }

  /**
   * Grants the approver's sign-off on a request.
   *
   * @param c the case, whose actor approves
   * @param requestId the request to approve
   * @return the outcome line
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String approve(@NotNull Case c, @NotNull UUID requestId) throws Exception {
    return render(send(c, post(ORG + "/requests/" + requestId + "/owner-approval")), _ -> "");
  }

  /**
   * Raises a request as the case's actor and then confirms or approves it as the same actor.
   *
   * @param c the case
   * @param source the source account
   * @param type the request type
   * @param confirm {@code true} to confirm, {@code false} to approve
   * @return the raise's outcome, then the decision's outcome
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String fourEyes(
      @NotNull Case c, @NotNull Account source, @NotNull String type, boolean confirm)
      throws Exception {
    BigDecimal amount = confirm ? Band.LOW.getAmount() : Band.MID.getAmount();
    MockHttpServletResponse raised = send(c, raiseCall(source, type, amount));
    String raiseOutcome = render(raised, this::routing);
    if (raised.getStatus() != 200) {
      return raiseOutcome;
    }
    entityManager.flush();
    UUID requestId = UUID.fromString(read(raised).path("id").asString());
    String decision = confirm ? confirm(c, requestId) : approve(c, requestId);
    return raiseOutcome + " then " + decision;
  }

  /**
   * Sends one call as the case's actor under the case's pin.
   *
   * @param c the case
   * @param call the call
   * @return the response
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private MockHttpServletResponse send(@NotNull Case c, @NotNull MockHttpServletRequestBuilder call)
      throws Exception {
    Actor actor = c.actor();
    String subject = world.user(actor).toString();
    String username = world.username(actor);
    call.contentType(MediaType.APPLICATION_JSON)
        .with(
            jwt()
                .jwt(token -> token.subject(subject).claim("preferred_username", username))
                .authorities(authorities));
    UUID pinned = world.pinned(c.pin());
    if (pinned != null) {
      call.header(RequestScopeResolver.ACTIVE_ORG_UNIT_HEADER, pinned.toString());
    }
    return mockMvc.perform(call).andReturn().getResponse();
  }

  /**
   * Renders a response: the status and the problem code of a refusal, or the status and the success
   * summary.
   *
   * @param response the response
   * @param summary renders the body of a success
   * @return the outcome line
   * @throws Exception when the body is no JSON
   */
  @NotNull
  private String render(
      @NotNull MockHttpServletResponse response, @NotNull Function<JsonNode, String> summary)
      throws Exception {
    int status = response.getStatus();
    if (status >= 400) {
      String code = read(response).path("code").asString();
      return code.isEmpty() ? String.valueOf(status) : status + " " + code;
    }
    String detail = summary.apply(read(response));
    return detail.isEmpty() ? String.valueOf(status) : status + " " + detail;
  }

  /**
   * Parses a response body, treating an empty body as JSON {@code null}.
   *
   * @param response the response
   * @return the parsed body
   * @throws Exception when the body cannot be read
   */
  @NotNull
  private JsonNode read(@NotNull MockHttpServletResponse response) throws Exception {
    String content = response.getContentAsString();
    return json.readTree(content.isBlank() ? "null" : content);
  }

  /**
   * Renders the approval routing of a returned request.
   *
   * @param request the request body
   * @return {@code approver=…} and {@code limit=…}
   */
  @NotNull
  private String routing(@NotNull JsonNode request) {
    String approver = request.path("requiredApprover").asString();
    return "approver="
        + (approver.isEmpty() ? "none" : approver)
        + " limit="
        + amount(request.path("applicableLimit"));
  }

  /**
   * Renders a direct booking: {@code booked}, or the routing of the request it was filed as.
   *
   * @param outcome the booking outcome body
   * @return the rendered outcome
   */
  @NotNull
  private String directOutcome(@NotNull JsonNode outcome) {
    JsonNode pending = outcome.path("pendingRequest");
    return pending.isObject() ? "filed " + routing(pending) : "booked";
  }

  /**
   * Renders the flags of a balance card.
   *
   * @param card the card
   * @return the set flags and the approval limit
   */
  @NotNull
  private String balanceFlags(@NotNull JsonNode card) {
    return flags(card, "canRequest", "canManageSettings", "approvalExempt")
        + " limit="
        + amount(card.path("approvalLimit"));
  }

  /**
   * Renders the capability flags of an org-unit drill-in.
   *
   * @param detail the detail
   * @return the set flags and the applicable limit
   */
  @NotNull
  private String detailFlags(@NotNull JsonNode detail) {
    return flags(
            detail,
            "canSetTarget",
            "canConfigureVisibility",
            "canRequest",
            "canConfigureApprovalLimits",
            "approvalExempt")
        + " limit="
        + amount(detail.path("applicableLimit"));
  }

  /**
   * Renders the capability flags of a settings panel.
   *
   * @param settings the settings
   * @return the set flags
   */
  @NotNull
  private String settingsFlags(@NotNull JsonNode settings) {
    return flags(settings, "canSetTarget", "canConfigureVisibility", "canConfigureApprovalLimits");
  }

  /**
   * Lists the names of the boolean fields that are {@code true}.
   *
   * @param node the object
   * @param fields the boolean fields to read
   * @return the true fields joined by commas, or {@value #NO_FLAGS}
   */
  @NotNull
  private static String flags(@NotNull JsonNode node, @NotNull String... fields) {
    List<String> set = new ArrayList<>();
    for (String field : fields) {
      if (node.path(field).asBoolean()) {
        set.add(field);
      }
    }
    return set.isEmpty() ? NO_FLAGS : String.join(",", set);
  }

  /**
   * Renders an amount without trailing zeros.
   *
   * @param node the amount, or a missing or {@code null} node
   * @return the plain amount, or {@code none}
   */
  @NotNull
  private static String amount(@NotNull JsonNode node) {
    return node.isNumber() ? node.decimalValue().stripTrailingZeros().toPlainString() : "none";
  }

  /**
   * Renders the seeded accounts of a listing in account declaration order.
   *
   * @param items the listed accounts
   * @param flags renders one listed account's flags
   * @return the seeded accounts with their flags, as {@code [A{…}, B{…}]}
   */
  @NotNull
  private String accountList(@NotNull JsonNode items, @NotNull Function<JsonNode, String> flags) {
    Map<Account, String> listed = new EnumMap<>(Account.class);
    for (JsonNode item : items) {
      JsonNode id = item.has("accountId") ? item.path("accountId") : item.path("id");
      Account account = world.accountOf(UUID.fromString(id.asString()));
      if (account != null) {
        listed.put(account, flags.apply(item));
      }
    }
    List<String> rendered = new ArrayList<>();
    listed.forEach((account, flag) ->
        rendered.add(NO_FLAGS.equals(flag) ? account.name() : account.name() + "{" + flag + "}"));
    return rendered.toString();
  }

  /**
   * Renders the seeded requests of a listing in request declaration order.
   *
   * @param items the listed requests
   * @param confirmFlag whether to mark the rows whose {@code callerMayConfirm} is true
   * @return the seeded requests, a confirmable one suffixed with {@code *}
   */
  @NotNull
  private String requestList(@NotNull JsonNode items, boolean confirmFlag) {
    Map<Request, Boolean> listed = new EnumMap<>(Request.class);
    for (JsonNode item : items) {
      Request request = world.requestOf(UUID.fromString(item.path("id").asString()));
      if (request != null) {
        listed.put(request, item.path("callerMayConfirm").asBoolean());
      }
    }
    List<String> rendered = new ArrayList<>();
    listed.forEach((request, confirmable) ->
        rendered.add(request.name() + (confirmFlag && confirmable ? "*" : "")));
    return rendered.toString();
  }

  /**
   * Serialises a request body.
   *
   * @param payload the fields
   * @return the JSON text
   */
  @NotNull
  private String body(@NotNull Map<String, ?> payload) {
    return json.writeValueAsString(payload);
  }

  /**
   * The id of the case's account.
   *
   * @param c the case
   * @return the account id
   */
  @NotNull
  private UUID accountId(@NotNull Case c) {
    return world.account((Account) c.subject());
  }

  /**
   * The id of the case's request.
   *
   * @param c the case
   * @return the request id
   */
  @NotNull
  private UUID requestId(@NotNull Case c) {
    return world.request((Request) c.subject());
  }

  /**
   * The stored version of the case's account.
   *
   * @param c the case
   * @return the account's version
   */
  private long accountVersion(@NotNull Case c) {
    return accountRepository.findById(accountId(c)).orElseThrow().getVersion();
  }

  /**
   * The stored version of the case's request.
   *
   * @param c the case
   * @return the request's version
   */
  private long requestVersion(@NotNull Case c) {
    return versionOf(requestId(c));
  }

  /**
   * The stored version of a request.
   *
   * @param requestId the request
   * @return the request's version
   */
  private long versionOf(@NotNull UUID requestId) {
    return requestRepository.findById(requestId).orElseThrow().getVersion();
  }

  /**
   * The vault holder every booking names.
   *
   * @return the holder id
   */
  @NotNull
  private UUID vault() {
    return world.holder(Holder.EMPLOYEE_DEPOSIT_HOLDER);
  }
}
