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
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Operation;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Pin;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.assertj.core.api.SoftAssertions;
import org.jetbrains.annotations.NotNull;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * The bank reads of the exchange answer every read case of the {@link BankAccessMatrix} as the web
 * recorded it (REQ-BXCH-001, REQ-BXCH-002): the account list as the balance cards without a pin,
 * reduced to the request flag and the limit, and the bookings and balance series of an account as
 * its read-only drill-in, reduced to the status.
 *
 * <p>The own-request writes answer every raise, correction and cancel case as the web recorded it
 * (REQ-BXCH-004); the driver cancels each raised request again, so the member stays below the
 * open-request cap and the test runs in the shared Spring context.
 *
 * <p>Two deviations are expected and pinned: an administrator acts on the exchange only through
 * their bank roles, grants and memberships (REQ-XCH-009), so the matrix's administrator without
 * memberships sees nothing; and a closed account is hidden on the exchange like an unknown one.
 */
@SpringBootTest
@Transactional
class BankExchangeParityTest {

  private static final Set<Operation> READS = Set.of(Operation.ORG_BALANCES, Operation.ORG_VIEW);

  private static final Pattern LISTED = Pattern.compile("([A-Z_]+)\\{([^ }]+) (limit=[^}]+)}");

  private static final Set<Operation> WRITES =
      Set.of(
          Operation.RAISE_DEPOSIT,
          Operation.RAISE_WITHDRAWAL,
          Operation.RAISE_TRANSFER,
          Operation.CORRECT_OWN,
          Operation.CANCEL_OWN);

  private static final Pattern ROUTED = Pattern.compile("^200 (approver=\\S+) limit=\\S+$");

  private static final String NOT_FOUND = "404 NOT_FOUND";

  @Autowired private WebApplicationContext context;

  @ParameterizedTest(name = "{0}")
  @EnumSource(Actor.class)
  void theExchangeAnswersEveryReadCaseOfTheActorAsTheWeb(Actor actor) throws Exception {
    Map<String, String> recorded = BankAccessOutcomes.recorded();
    BankExchangeAccessDriver driver =
        new BankExchangeAccessDriver(context, new BankAccessSeeder(context).seed());

    SoftAssertions softly = new SoftAssertions();
    for (Case c : BankAccessMatrix.casesOf(actor)) {
      if (!READS.contains(c.operation()) || c.pin() != Pin.NONE) {
        continue;
      }
      softly.assertThat(driver.run(c)).as(c.key()).isEqualTo(expected(c, recorded.get(c.key())));
    }
    softly.assertAll();
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Actor.class)
  void theExchangeRaisesCorrectsAndCancelsAsTheWeb(Actor actor) throws Exception {
    Map<String, String> recorded = BankAccessOutcomes.recorded();
    BankExchangeAccessDriver driver =
        new BankExchangeAccessDriver(context, new BankAccessSeeder(context).seed());

    SoftAssertions softly = new SoftAssertions();
    for (Case c : BankAccessMatrix.casesOf(actor)) {
      if (!WRITES.contains(c.operation()) || c.pin() != Pin.NONE) {
        continue;
      }
      softly
          .assertThat(driver.run(c))
          .as(c.key())
          .isEqualTo(expectedWrite(c, recorded.get(c.key())));
    }
    softly.assertAll();
  }

  /**
   * The exchange outcome of a raise, correction or cancel, derived from the web's: a raise answers
   * {@code 201}, a refused or closed account the shared {@code 404}, and the routing stays without
   * the limit, which the exchange does not expose. A pending registration is refused by the
   * acting-member gate, and an administrator without memberships may not debit any account
   * (REQ-XCH-009).
   *
   * @param c the case
   * @param web the web's recorded outcome
   * @return the expected exchange outcome
   */
  @NotNull
  private static String expectedWrite(@NotNull Case c, @NotNull String web) {
    if (c.actor() == Actor.PENDING) {
      return "403 PENDING_APPROVAL";
    }
    boolean debit =
        c.operation() == Operation.RAISE_WITHDRAWAL || c.operation() == Operation.RAISE_TRANSFER;
    if (web.startsWith("403")
        || web.equals("409 BANK_ACCOUNT_CLOSED")
        || c.subject() == Account.CLOSED
        || (debit && c.actor() == Actor.ADMIN)) {
      return NOT_FOUND;
    }
    Matcher routed = ROUTED.matcher(web);
    boolean raise = c.operation() != Operation.CORRECT_OWN && c.operation() != Operation.CANCEL_OWN;
    if (routed.matches()) {
      return (raise ? "201 " : "200 ") + routed.group(1);
    }
    return web;
  }

  /**
   * The exchange outcome a case must have, derived from the web's recorded outcome.
   *
   * @param c the case
   * @param web the web's recorded outcome
   * @return the expected exchange outcome
   */
  @NotNull
  private static String expected(@NotNull Case c, @NotNull String web) {
    boolean admin = c.actor() == Actor.ADMIN;
    return switch (c.operation()) {
      case ORG_BALANCES -> admin ? "200 []" : requestFlags(web);
      case ORG_VIEW ->
          admin || (c.subject() == Account.CLOSED && web.startsWith("200"))
              ? NOT_FOUND
              : status(web);
      default -> throw new IllegalArgumentException("Not an exchange read: " + c.operation());
    };
  }

  /**
   * Reduces the balance cards to the request flag and the limit of each account.
   *
   * @param web the recorded balance cards
   * @return the cards with only {@code canRequest} left of the flags
   */
  @NotNull
  private static String requestFlags(@NotNull String web) {
    Matcher listed = LISTED.matcher(web);
    StringBuilder reduced = new StringBuilder();
    while (listed.find()) {
      String flags = Set.of(listed.group(2).split(",")).contains("canRequest") ? "canRequest" : "-";
      listed.appendReplacement(
          reduced, listed.group(1) + "{" + flags + " " + listed.group(3) + "}");
    }
    listed.appendTail(reduced);
    return reduced.toString();
  }

  /**
   * Reduces a drill-in outcome to its status and refusal code.
   *
   * @param web the recorded drill-in outcome
   * @return {@code 200}, or the status and code of the refusal
   */
  @NotNull
  private static String status(@NotNull String web) {
    return web.startsWith("200") ? "200" : web;
  }
}
