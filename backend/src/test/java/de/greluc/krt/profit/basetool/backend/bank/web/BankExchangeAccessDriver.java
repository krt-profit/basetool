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

import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Account;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Case;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Request;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Runs the read and own-request cases of the {@link BankAccessMatrix} through the bank routes of
 * the exchange and renders each outcome in the vocabulary of {@link BankWebAccessDriver}.
 *
 * <p>{@code ORG_BALANCES} runs as the account list and renders each listed account with {@code
 * canRequest} for {@code requestable} and the {@code ownLimit}; {@code ORG_VIEW} runs as the
 * bookings and the balance series of the account, which must answer alike, and renders the status
 * and refusal code. The raise and correction cases render the routing as the own requests answer
 * it, without the limit, which the exchange does not expose; the cancel case renders the status.
 */
final class BankExchangeAccessDriver {

  private static final String NO_FLAGS = "-";

  private final BankAccessWorld world;
  private final BankExchangeRelay relay;

  /**
   * Binds the driver to one seeded world.
   *
   * @param context the running web application context
   * @param world the world the cases run against
   */
  BankExchangeAccessDriver(@NotNull WebApplicationContext context, @NotNull BankAccessWorld world) {
    this.world = world;
    this.relay = new BankExchangeRelay(context);
  }

  /**
   * Runs one read or own-request case.
   *
   * @param c a case of {@code ORG_BALANCES}, {@code ORG_VIEW}, a raise, {@code CORRECT_OWN} or
   *     {@code CANCEL_OWN}
   * @return the outcome line
   * @throws Exception when MockMvc fails to dispatch
   * @throws IllegalArgumentException for an operation the exchange does not serve
   */
  @NotNull
  String run(@NotNull Case c) throws Exception {
    UUID member = world.user(c.actor());
    return switch (c.operation()) {
      case ORG_BALANCES ->
          render(relay.read(member, "/accounts", Map.of("limit", "1000")), this::accountList);
      case ORG_VIEW -> view(member, world.account((Account) c.subject()));
      case RAISE_DEPOSIT -> raise(c, "DEPOSIT");
      case RAISE_WITHDRAWAL -> raise(c, "WITHDRAWAL");
      case RAISE_TRANSFER -> raise(c, "TRANSFER");
      case CORRECT_OWN -> correct(c);
      case CANCEL_OWN -> cancel(c);
      default -> throw new IllegalArgumentException("Not an exchange read: " + c.operation());
    };
  }

  /**
   * Raises a request on the case's account, with the web driver's justification and transfer
   * destination.
   *
   * @param c the case
   * @param type the request type
   * @return the status and the routing, or the refusal
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String raise(@NotNull Case c, @NotNull String type) throws Exception {
    Account source = (Account) c.subject();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("type", type);
    body.put("sourceAccountId", world.account(source));
    body.put("amount", c.band().getAmount());
    if (!"DEPOSIT".equals(type)) {
      body.put("justification", BankAccessSeeder.JUSTIFICATION);
    }
    if ("TRANSFER".equals(type)) {
      body.put("targetAccountId", world.transferTargetFor(source));
    }
    UUID member = world.user(c.actor());
    return render(
        relay.write(HttpMethod.POST, member, "/requests", body),
        answer -> routing(member, answer.path("id").asString()));
  }

  /**
   * Corrects the member's own request to the case's amount, keeping the justification.
   *
   * @param c the case
   * @return the status and the routing, or the refusal
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String correct(@NotNull Case c) throws Exception {
    UUID member = world.user(c.actor());
    UUID request = world.request((Request) c.subject());
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("amount", c.band().getAmount());
    body.put("justification", BankAccessSeeder.JUSTIFICATION);
    body.put("version", ownerVersion(request));
    return render(
        relay.write(HttpMethod.PUT, member, "/requests/" + request, body),
        answer -> routing(member, answer.path("id").asString()));
  }

  /**
   * Cancels the member's own request.
   *
   * @param c the case
   * @return the status, or the refusal
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String cancel(@NotNull Case c) throws Exception {
    UUID request = world.request((Request) c.subject());
    return render(
        relay.write(
            HttpMethod.POST,
            world.user(c.actor()),
            "/requests/" + request + "/cancel",
            Map.of("version", ownerVersion(request))),
        _ -> "");
  }

  /**
   * Renders the routing of a written request as the member's own requests answer it.
   *
   * @param member the requester
   * @param id the request
   * @return {@code approver=…}
   */
  @NotNull
  private String routing(@NotNull UUID member, @NotNull String id) {
    JsonNode own = ownRequest(member, id);
    String approver = own.path("requiredApprover").asString();
    return "approver=" + (approver.isEmpty() ? "none" : approver);
  }

  /**
   * The version of a seeded request as its requester reads it.
   *
   * @param request the request
   * @return its version
   */
  private long ownerVersion(@NotNull UUID request) {
    return ownRequest(world.user(Actor.MEMBER), request.toString()).path("version").asLong();
  }

  /**
   * One of a member's own requests.
   *
   * @param member the member
   * @param id the request
   * @return its item
   * @throws IllegalStateException when the member's own requests do not list it
   */
  @NotNull
  private JsonNode ownRequest(@NotNull UUID member, @NotNull String id) {
    try {
      for (JsonNode item :
          relay.body(relay.read(member, "/requests", Map.of("limit", "1000"))).path("items")) {
        if (id.equals(item.path("id").asString())) {
          return item;
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("The own requests could not be read", e);
    }
    throw new IllegalStateException(id + " is not among the member's own requests");
  }

  /**
   * Reads the bookings and the balance series of an account.
   *
   * @param member the acting member
   * @param accountId the account
   * @return the shared outcome, or both outcomes when they differ
   * @throws Exception when MockMvc fails to dispatch
   */
  @NotNull
  private String view(@NotNull UUID member, @NotNull UUID accountId) throws Exception {
    Instant now = Instant.now();
    String bookings =
        render(relay.read(member, "/accounts/" + accountId + "/bookings", Map.of()), _ -> "");
    String series =
        render(
            relay.read(
                member,
                "/accounts/" + accountId + "/balance-series",
                Map.of("from", now.minus(30, ChronoUnit.DAYS).toString(), "to", now.toString())),
            _ -> "");
    return bookings.equals(series) ? bookings : "bookings " + bookings + " series " + series;
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
    JsonNode body = relay.body(response);
    if (status >= 400) {
      String code = body.path("code").asString();
      return code.isEmpty() ? String.valueOf(status) : status + " " + code;
    }
    String detail = summary.apply(body);
    return detail.isEmpty() ? String.valueOf(status) : status + " " + detail;
  }

  /**
   * Renders the seeded accounts of a page in account declaration order.
   *
   * @param page the account page
   * @return the seeded accounts with their request flag and limit, as {@code [A{…}, B{…}]}
   */
  @NotNull
  private String accountList(@NotNull JsonNode page) {
    Map<Account, String> listed = new EnumMap<>(Account.class);
    for (JsonNode item : page.path("items")) {
      Account account = world.accountOf(UUID.fromString(item.path("accountId").asString()));
      if (account != null) {
        JsonNode limit = item.path("ownLimit");
        listed.put(
            account,
            (item.path("requestable").asBoolean() ? "canRequest" : NO_FLAGS)
                + " limit="
                + (limit.isNumber() ? limit.asString() : "none"));
      }
    }
    List<String> rendered = new ArrayList<>();
    listed.forEach((account, flags) -> rendered.add(account.name() + "{" + flags + "}"));
    return rendered.toString();
  }
}
