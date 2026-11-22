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

package de.greluc.krt.profit.basetool.backend.bank.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.kernel.HandleAnonymisation;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** The booking-request area of the name retention against Postgres (REQ-DATA-047). */
@SpringBootTest
@Transactional
class BookingRequestNameRetentionTest {

  private static final Instant CUTOFF = Instant.parse("2026-01-01T00:00:00Z");

  @Autowired private BookingRequestNameRetention retention;
  @Autowired private BankAccountRepository accountRepository;
  @Autowired private JdbcTemplate jdbc;

  @Test
  void aRequestDecidedBeforeTheCutoffLosesItsTextsAndHandles() {
    UUID account = account();
    UUID old = decidedRequest(account, CUTOFF.minus(1, ChronoUnit.DAYS));
    UUID recent = decidedRequest(account, CUTOFF.plus(1, ChronoUnit.DAYS));

    assertThat(retention.removeNamesBefore(CUTOFF)).isGreaterThanOrEqualTo(1);

    Map<String, Object> cleared = row(old);
    assertThat(cleared.get("note")).isNull();
    assertThat(cleared.get("reject_reason")).isNull();
    assertThat(cleared.get("staff_note")).isNull();
    assertThat(cleared.get("requester_handle")).isEqualTo(HandleAnonymisation.SENTINEL);
    assertThat(cleared.get("decider_handle")).isEqualTo(HandleAnonymisation.SENTINEL);
    assertThat(row(recent).get("note")).isEqualTo("Note text");
    assertThat(row(recent).get("requester_handle")).isEqualTo("Requester");
  }

  @Test
  void aSecondRunChangesNothingAndAPendingRequestStays() {
    UUID account = account();
    decidedRequest(account, CUTOFF.minus(1, ChronoUnit.DAYS));
    UUID pending = UUID.randomUUID();
    jdbc.update(
        "INSERT INTO bank_booking_request (id, account_id, type, amount, requester_handle, note)"
            + " VALUES (?, ?, 'DEPOSIT', 10, 'Pending Requester', 'Pending note')",
        pending,
        account);
    retention.removeNamesBefore(CUTOFF);

    assertThat(retention.removeNamesBefore(CUTOFF)).isZero();
    assertThat(row(pending).get("note")).isEqualTo("Pending note");
  }

  private UUID decidedRequest(UUID account, Instant decidedAt) {
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        INSERT INTO bank_booking_request
            (id, account_id, type, amount, requester_handle, decider_handle, note,
             reject_reason, staff_note, status, decided_at)
        VALUES (?, ?, 'DEPOSIT', 10, 'Requester', 'Decider', 'Note text', 'Reject reason',
                'Staff note', 'REJECTED', ?)
        """,
        id,
        account,
        Timestamp.from(decidedAt));
    return id;
  }

  private Map<String, Object> row(UUID request) {
    return jdbc.queryForMap("SELECT * FROM bank_booking_request WHERE id = ?", request);
  }

  private UUID account() {
    BankAccount created = new BankAccount();
    created.setAccountNo(String.format("KB-%04d", accountRepository.nextAccountNoValue()));
    String name = "Retention " + UUID.randomUUID().toString().substring(0, 8);
    created.setName(name);
    created.setType(BankAccountType.AREA);
    created.setAreaName(name);
    created.setStatus(BankAccountStatus.ACTIVE);
    return accountRepository.saveAndFlush(created).getId();
  }
}
