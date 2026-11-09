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

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Actor;
import de.greluc.krt.profit.basetool.backend.bank.web.BankAccessMatrix.Case;
import de.greluc.krt.profit.basetool.backend.platform.api.RateLimitProperties;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * Pins the web outcome of every bank access rule over the {@link BankAccessMatrix} against
 * Postgres, as recorded in {@link BankAccessOutcomes#RESOURCE}: who sees, lists, raises, corrects,
 * cancels, confirms, rejects and approves what, at which amount band and under which pin, with the
 * configuration rights, the holder view and the staff direct bookings.
 *
 * <p>Each actor runs all of its cases in one rolled-back transaction, on a freshly seeded world
 * that is replaced before the actor's writes would exceed the per-subject write budget
 * (REQ-SEC-033). A run writes the outcomes it saw to {@link BankAccessOutcomes#ACTUAL}.
 */
@SpringBootTest
@Transactional
class BankAccessCharacterisationTest {

  private static final Map<String, String> OBSERVED = new ConcurrentHashMap<>();

  @Autowired private WebApplicationContext context;

  @AfterAll
  static void writeObservedOutcomes() {
    BankAccessOutcomes.writeActual(BankAccessMatrix.allCases(), OBSERVED);
  }

  @ParameterizedTest(name = "{0}")
  @EnumSource(Actor.class)
  void theWebAnswersEveryCaseOfTheActorAsRecorded(Actor actor) throws Exception {
    int writeBudget = context.getBean(RateLimitProperties.class).subject().capacity();
    Map<String, String> recorded = BankAccessOutcomes.recorded();
    BankWebAccessDriver driver = freshWorld();
    int writesSpent = 0;

    SoftAssertions softly = new SoftAssertions();
    for (Case c : BankAccessMatrix.casesOf(actor)) {
      int writes = c.operation().getWriteCalls();
      if (writesSpent + writes > writeBudget) {
        driver = freshWorld();
        writesSpent = 0;
      }
      writesSpent += writes;
      String outcome = driver.run(c);
      OBSERVED.put(c.key(), outcome);
      softly
          .assertThat(outcome)
          .as("%s (observed outcomes: %s)", c.key(), BankAccessOutcomes.ACTUAL)
          .isEqualTo(recorded.get(c.key()));
    }
    softly.assertAll();
  }

  /**
   * Seeds a new world with new users, whose per-subject write budgets are untouched.
   *
   * @return a driver bound to the new world
   */
  private BankWebAccessDriver freshWorld() {
    return new BankWebAccessDriver(context, new BankAccessSeeder(context).seed());
  }

  @Test
  void theRecordingHoldsExactlyTheCasesOfTheMatrix() {
    List<String> keys = BankAccessMatrix.allCases().stream().map(Case::key).toList();

    assertThat(BankAccessOutcomes.recorded().keySet()).containsExactlyInAnyOrderElementsOf(keys);
  }
}
