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

package de.greluc.krt.profit.basetool.frontend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderStoreForm;
import de.greluc.krt.profit.basetool.frontend.model.form.RefineryOrderStoreItemForm;
import de.greluc.krt.profit.basetool.frontend.service.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.websocket.LiveSyncTopicClass;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/**
 * Tests the server-side live-sync publish of the refinery queue (REQ-FE-015, ADR-0094): the poke
 * fires on success, not after a backend refusal, and the store path also pokes the shared Lager.
 */
class RefineryOrderLiveSyncPublishTest {

  /** The queue section of the global {@code refinery} room. */
  private static final List<String> QUEUE = List.of("queue");

  /** The shared-Lager section of the global {@code inventory} room (owned by INVENTORY_ALL). */
  private static final List<String> STOCK = List.of("stock");

  private BackendApiClient backendApiClient;
  private LiveSyncLocalBus liveSyncLocalBus;
  private RefineryOrderWriteController controller;
  private RedirectAttributes redirectAttributes;

  @BeforeEach
  void setUp() {
    backendApiClient = mock(BackendApiClient.class);
    liveSyncLocalBus = mock(LiveSyncLocalBus.class);
    controller = new RefineryOrderWriteController(backendApiClient, liveSyncLocalBus);
    redirectAttributes = new RedirectAttributesModelMap();
  }

  @Test
  void topicsAndSectionsUsedHere_areTheOnesTheRegistryWhitelists() {
    assertThat(LiveSyncTopicClass.REFINERY.prefix()).isEqualTo("refinery");
    assertThat(LiveSyncTopicClass.REFINERY.allowedSections()).containsExactlyElementsOf(QUEUE);
    assertThat(LiveSyncTopicClass.INVENTORY_ALL.prefix()).isEqualTo("inventory");
    assertThat(LiveSyncTopicClass.INVENTORY_ALL.allowedSections()).containsExactlyElementsOf(STOCK);
  }

  @Test
  void deleteOrder_publishesTheQueueSection() {
    controller.deleteOrder(UUID.randomUUID(), redirectAttributes);

    verify(liveSyncLocalBus).publish("refinery", QUEUE);
  }

  @Test
  void deleteOrder_onBackendFailure_doesNotPublish() {
    doThrow(new RuntimeException("backend down")).when(backendApiClient).delete(anyString(), any());

    controller.deleteOrder(UUID.randomUUID(), redirectAttributes);

    verify(liveSyncLocalBus, never()).publish(anyString(), any());
  }

  @Test
  void deleteOrderAjax_publishesTheQueueSection() {
    controller.deleteOrderAjax(UUID.randomUUID());

    verify(liveSyncLocalBus).publish("refinery", QUEUE);
  }

  @Test
  void deleteOrderAjax_onBackendFailure_doesNotPublish() {
    doThrow(new RuntimeException("backend down")).when(backendApiClient).delete(anyString(), any());

    controller.deleteOrderAjax(UUID.randomUUID());

    verify(liveSyncLocalBus, never()).publish(anyString(), any());
  }

  @Test
  void storeOrder_publishesTheQueueSectionAndPokesTheSharedLager() {
    UUID id = UUID.randomUUID();

    controller.storeOrder(id, storeForm(), noErrors(), redirectAttributes);

    verify(liveSyncLocalBus).publish("refinery", QUEUE);
    verify(liveSyncLocalBus).publish("inventory", STOCK);
  }

  @Test
  void storeOrderAjax_publishesTheQueueSectionAndPokesTheSharedLager() {
    UUID id = UUID.randomUUID();

    controller.storeOrderAjax(id, storeForm(), noErrors());

    verify(liveSyncLocalBus).publish("refinery", QUEUE);
    verify(liveSyncLocalBus).publish("inventory", STOCK);
  }

  @Test
  void storeOrderAjax_onBackendFailure_pokesNeitherRoom() {
    UUID id = UUID.randomUUID();
    doThrow(new RuntimeException("backend down"))
        .when(backendApiClient)
        .post(anyString(), any(), eq(Void.class));

    controller.storeOrderAjax(id, storeForm(), noErrors());

    verify(liveSyncLocalBus, never()).publish(anyString(), any());
  }

  @Test
  void storeOrderAjax_onValidationFailure_pokesNeitherRoom() {
    UUID id = UUID.randomUUID();
    BindingResult errors = noErrors();
    errors.reject("invalid");

    controller.storeOrderAjax(id, storeForm(), errors);

    verify(liveSyncLocalBus, never()).publish(anyString(), any());
  }

  /**
   * Builds a minimal, valid single-item store form — enough for {@code buildStoreDto} to produce a
   * DTO; the backend call itself is mocked, so the field values are irrelevant beyond being
   * non-null where the DTO reads them.
   *
   * @return a store form carrying one item
   */
  private static RefineryOrderStoreForm storeForm() {
    RefineryOrderStoreItemForm item = new RefineryOrderStoreItemForm();
    item.setMaterialId(UUID.randomUUID());
    item.setLocationId(UUID.randomUUID());
    item.setUserId(UUID.randomUUID());
    RefineryOrderStoreForm form = new RefineryOrderStoreForm();
    form.setItems(List.of(item));
    return form;
  }

  /**
   * An empty {@link BindingResult} for the bound store form, so the handler takes its success path.
   *
   * @return a binding result with no errors
   */
  private static BindingResult noErrors() {
    return new BeanPropertyBindingResult(new RefineryOrderStoreForm(), "storeForm");
  }
}
