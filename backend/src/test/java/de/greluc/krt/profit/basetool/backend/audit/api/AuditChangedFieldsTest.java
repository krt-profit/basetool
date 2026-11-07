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

package de.greluc.krt.profit.basetool.backend.audit.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link AuditChangedFields}: only differing fields are named, in call order. */
class AuditChangedFieldsTest {

  @Test
  void namesOnlyTheFieldsWhoseValuesDiffer_inCallOrder() {
    AuditChangedFields changed =
        AuditChangedFields.create()
            .compare("name", "Alpha", "Beta")
            .compare("description", "same", "same")
            .compare("category", null, 7)
            .compare("hidden", false, false);

    assertThat(changed.isEmpty()).isFalse();
    assertThat(changed.toDetails()).hasToString("changed=name,category");
  }

  @Test
  void isEmptyWhenNothingDiffers() {
    AuditChangedFields changed =
        AuditChangedFields.create().compare("name", "Alpha", "Alpha").compare("x", null, null);

    assertThat(changed.isEmpty()).isTrue();
  }
}
