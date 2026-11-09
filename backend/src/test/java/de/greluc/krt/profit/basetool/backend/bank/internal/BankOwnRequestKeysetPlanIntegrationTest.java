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

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * A page of a member's own requests in id order, the exchange snapshot's keyset (REQ-BXCH-003), is
 * read straight from an index in that order, with no sort over the member's whole history.
 */
@SpringBootTest
@Transactional
class BankOwnRequestKeysetPlanIntegrationTest {

  @Autowired private JdbcTemplate jdbc;

  @Test
  void aKeysetPageIsReadInIndexOrderWithoutASort() {
    jdbc.execute("SET LOCAL enable_seqscan = off");
    String member = UUID.randomUUID().toString();
    String after = UUID.randomUUID().toString();

    List<String> plan =
        jdbc.queryForList(
            "EXPLAIN SELECT id FROM bank_booking_request"
                + " WHERE requested_by = '"
                + member
                + "' AND id > '"
                + after
                + "' ORDER BY id LIMIT 500",
            String.class);

    assertThat(plan).noneMatch(line -> line.contains("Sort"));
    assertThat(String.join("\n", plan)).contains("idx_bank_booking_request_requested_by_id");
  }
}
