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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicResponse;
import de.greluc.krt.profit.basetool.backend.model.dto.PromotionTopicWriteRequest;
import de.greluc.krt.profit.basetool.backend.service.PromotionTopicService;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

/**
 * Pure-Mockito unit tests for {@link PromotionTopicController}. The controller is a thin pass-
 * through to {@link PromotionTopicService}; the tests pin down two contracts that the pass-through
 * must not silently break: the pagination wrapper translates a Spring {@link Page} into the
 * project's {@link PageResponse} record (content/page/size/totalElements/totalPages/sort
 * one-to-one), and the create/update/delete endpoints forward their inputs to the service verbatim.
 * There is no integration test using {@code @WebMvcTest} here on purpose — the project memory pins
 * us to Mockito-only because the local TestContainers stack does not run reliably, and this
 * controller's logic is small enough that the slice test adds no signal beyond what these unit
 * tests already pin.
 */
@ExtendWith(MockitoExtension.class)
class PromotionTopicControllerTest {

  @Mock private PromotionTopicService service;

  @InjectMocks private PromotionTopicController controller;

  private static PromotionTopicResponse topic(String name) {
    Instant ts = Instant.parse("2026-05-15T12:00:00Z");
    return new PromotionTopicResponse(UUID.randomUUID(), 1L, name, "desc", 1, null, ts, ts);
  }

  @Test
  void list_wrapsServicePageIntoPageResponse_andForwardsPageable() {
    PromotionTopicResponse t = topic("Combat");
    Page<PromotionTopicResponse> page =
        new PageImpl<>(
            List.of(t), PageRequest.of(0, 20, Sort.by(Sort.Direction.ASC, "sortOrder")), 1);
    when(service.list(any(Pageable.class))).thenReturn(page);

    PageResponse<PromotionTopicResponse> result = controller.list(0, 20, "sortOrder,asc");

    assertThat(result.content()).containsExactly(t);
    assertThat(result.page()).isZero();
    assertThat(result.size()).isEqualTo(20);
    assertThat(result.totalElements()).isEqualTo(1L);
    assertThat(result.totalPages()).isEqualTo(1);
    assertThat(result.sort()).containsExactly("sortOrder,asc");

    ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
    verify(service).list(captor.capture());
    assertThat(captor.getValue().getPageNumber()).isZero();
    assertThat(captor.getValue().getPageSize()).isEqualTo(20);
  }

  @Test
  void list_withNullParams_appliesPaginationDefaults() {
    when(service.list(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

    PageResponse<PromotionTopicResponse> result = controller.list(null, null, null);

    assertThat(result).isNotNull();
    assertThat(result.content()).isEmpty();
    verify(service).list(any(Pageable.class));
  }

  @Test
  void listAll_returnsServiceListVerbatim() {
    PromotionTopicResponse a = topic("Combat");
    PromotionTopicResponse b = topic("Logistics");
    when(service.listAll()).thenReturn(List.of(a, b));

    List<PromotionTopicResponse> result = controller.listAll();

    assertThat(result).containsExactly(a, b);
    verify(service).listAll();
  }

  @Test
  void get_returnsServiceResponseVerbatim() {
    UUID id = UUID.randomUUID();
    PromotionTopicResponse response = topic("Combat");
    when(service.get(id)).thenReturn(response);

    PromotionTopicResponse result = controller.get(id);

    assertThat(result).isSameAs(response);
    verify(service).get(id);
  }

  @Test
  void create_forwardsRequestToService_andReturnsCreatedEntity() {
    PromotionTopicWriteRequest request = new PromotionTopicWriteRequest("Combat", "desc", 1, null);
    PromotionTopicResponse created = topic("Combat");
    when(service.create(request)).thenReturn(created);

    PromotionTopicResponse result = controller.create(request);

    assertThat(result).isSameAs(created);
    verify(service).create(request);
  }

  @Test
  void update_forwardsBothPathAndBodyToService() {
    UUID id = UUID.randomUUID();
    PromotionTopicWriteRequest request =
        new PromotionTopicWriteRequest("Combat updated", "desc", 1, 3L);
    PromotionTopicResponse updated = topic("Combat updated");
    when(service.update(id, request)).thenReturn(updated);

    PromotionTopicResponse result = controller.update(id, request);

    assertThat(result).isSameAs(updated);
    verify(service).update(id, request);
  }

  @Test
  void delete_delegatesToService() {
    UUID id = UUID.randomUUID();

    controller.delete(id);

    verify(service).delete(eq(id));
    verifyNoMoreInteractions(service);
  }
}
