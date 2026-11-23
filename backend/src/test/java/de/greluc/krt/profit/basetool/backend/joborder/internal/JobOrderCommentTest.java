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

package de.greluc.krt.profit.basetool.backend.joborder.internal;

import static org.assertj.core.api.Assertions.assertThat;

import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrder;
import de.greluc.krt.profit.basetool.backend.joborder.api.JobOrderActor;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The comment remembers the client of its last write beside its revision: a client's write sets it,
 * a web write clears it, and a write that leaves the text as it is moves neither (REQ-ORDERS-078).
 */
class JobOrderCommentTest {

  private static final JobOrderActor CLIENT =
      new JobOrderActor.Client(UUID.randomUUID(), "versekit");

  private static final JobOrderActor WEB = new JobOrderActor.Web(UUID.randomUUID());

  @Test
  void aClientsWriteRecordsTheClient() {
    JobOrder order = new JobOrder();

    assertThat(JobOrderComment.replace(order, "Deliver to Area18", CLIENT)).isTrue();

    assertThat(order.getComment()).isEqualTo("Deliver to Area18");
    assertThat(order.getCommentVersion()).isEqualTo(1L);
    assertThat(order.getCommentClientId()).isEqualTo("versekit");
  }

  @Test
  void aWebWriteClearsTheClient() {
    JobOrder order = new JobOrder();
    JobOrderComment.replace(order, "Deliver to Area18", CLIENT);

    assertThat(JobOrderComment.replace(order, "Deliver to Lorville", WEB)).isTrue();

    assertThat(order.getCommentVersion()).isEqualTo(2L);
    assertThat(order.getCommentClientId()).isNull();
  }

  @Test
  void anUnchangedTextKeepsTheClientOfTheLastWrite() {
    JobOrder order = new JobOrder();
    JobOrderComment.replace(order, "Deliver to Area18", CLIENT);

    assertThat(JobOrderComment.replace(order, " Deliver to Area18 ", WEB)).isFalse();

    assertThat(order.getCommentVersion()).isEqualTo(1L);
    assertThat(order.getCommentClientId()).isEqualTo("versekit");
  }
}
