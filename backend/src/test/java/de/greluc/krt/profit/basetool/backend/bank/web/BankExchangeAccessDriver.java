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
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Case;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.jetbrains.annotations.NotNull;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;

/**
 * Runs the read cases of the {@link BankAccessMatrix} through the bank routes of the exchange and
 * renders each outcome in the vocabulary of {@link BankWebAccessDriver}.
 *
 * <p>{@code ORG_BALANCES} runs as the account list and renders each listed account with {@code
 * canRequest} for {@code requestable} and the {@code ownLimit}; {@code ORG_VIEW} runs as the
 * bookings and the balance series of the account, which must answer alike, and renders the status
 * and refusal code.
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
   * Runs one read case.
   *
   * @param c a case of {@code ORG_BALANCES} or {@code ORG_VIEW}
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
      default -> throw new IllegalArgumentException("Not an exchange read: " + c.operation());
    };
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
