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

package de.greluc.krt.profit.basetool.backend.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.DefaultBlueprintCreateRequest;
import de.greluc.krt.profit.basetool.backend.model.dto.DefaultBlueprintResponse;
import de.greluc.krt.profit.basetool.backend.service.DefaultBlueprintService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** Unit tests for {@link AdminDefaultBlueprintController}. */
@ExtendWith(MockitoExtension.class)
class AdminDefaultBlueprintControllerTest {

  @Mock private DefaultBlueprintService service;
  @InjectMocks private AdminDefaultBlueprintController controller;

  private static DefaultBlueprintResponse sample() {
    return new DefaultBlueprintResponse(
        UUID.randomUUID(), "k", "Name", null, null, "system", Instant.EPOCH, 0L);
  }

  @Test
  void list_delegatesToService() {
    when(service.list()).thenReturn(List.of(sample()));

    assertEquals(1, controller.list().size());
    verify(service).list();
  }

  @Test
  void add_passesProductKeyAndAdminSub() {
    when(service.add("k", "admin-sub")).thenReturn(sample());

    controller.add(new DefaultBlueprintCreateRequest("k"), "admin-sub");

    verify(service).add("k", "admin-sub");
  }

  @Test
  void remove_delegatesToService() {
    UUID id = UUID.randomUUID();

    controller.remove(id);

    verify(service).remove(id);
  }
}
