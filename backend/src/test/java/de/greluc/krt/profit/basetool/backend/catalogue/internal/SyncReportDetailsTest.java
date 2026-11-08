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

package de.greluc.krt.profit.basetool.backend.catalogue.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** The details of a sync report event read {@code key=value} entries and flags joined by ';'. */
class SyncReportDetailsTest {

  @Test
  void entriesAndFlags_renderInOrder_joinedBySemicolons() {
    String details =
        SyncReportDetails.of("retiredItem", 7)
            .with("survivorItem", null)
            .flag("conflict")
            .toString();

    assertThat(details).isEqualTo("retiredItem=7;survivorItem=null;conflict");
  }

  @Test
  void aKeyThatWouldBreakTheGrammar_isRefused() {
    assertThatThrownBy(() -> SyncReportDetails.of("a=b", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SyncReportDetails.of("a", 1).flag("x;y"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> SyncReportDetails.of("", 1))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
