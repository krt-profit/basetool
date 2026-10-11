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

package de.greluc.krt.profit.basetool.backend.joborder.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.backend.config.SecurityConfig;
import de.greluc.krt.profit.basetool.backend.kernel.Roles;
import de.greluc.krt.profit.basetool.backend.model.dto.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.backend.model.dto.ItemDerivationDto;
import de.greluc.krt.profit.basetool.backend.model.dto.PageResponse;
import de.greluc.krt.profit.basetool.backend.service.InventoryItemCatalogService;
import de.greluc.krt.profit.basetool.backend.service.JobOrderItemService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.objenesis.ObjenesisStd;
import org.springframework.security.access.expression.method.DefaultMethodSecurityExpressionHandler;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.authorization.DefaultAuthorizationManagerFactory;
import org.springframework.security.authorization.method.PreAuthorizeAuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.util.SimpleMethodInvocation;

/**
 * Unit tests for {@link GameItemController}, the one game-item catalogue behind the order and Lager
 * pickers: the {@code orderable} switch picks the query, the sort whitelist and the gate of the
 * folded read keep both former audiences exactly.
 */
@ExtendWith(MockitoExtension.class)
class GameItemControllerTest {

  private static final ObjenesisStd OBJENESIS = new ObjenesisStd();
  private static final PreAuthorizeAuthorizationManager MANAGER = manager();

  @Mock private InventoryItemCatalogService inventoryItemCatalogService;
  @Mock private JobOrderItemService jobOrderItemService;
  @InjectMocks private GameItemController controller;

  private static PreAuthorizeAuthorizationManager manager() {
    DefaultMethodSecurityExpressionHandler handler = new DefaultMethodSecurityExpressionHandler();
    DefaultAuthorizationManagerFactory<MethodInvocation> factory =
        new DefaultAuthorizationManagerFactory<>();
    factory.setRoleHierarchy(SecurityConfig.roleHierarchy());
    handler.setAuthorizationManagerFactory(factory);
    PreAuthorizeAuthorizationManager manager = new PreAuthorizeAuthorizationManager();
    manager.setExpressionHandler(handler);
    return manager;
  }

  private static Method getGameItems() {
    return Arrays.stream(GameItemController.class.getDeclaredMethods())
        .filter(m -> m.getName().equals("getGameItems"))
        .findFirst()
        .orElseThrow();
  }

  private static boolean granted(boolean orderable, String... authorities) {
    Authentication authentication =
        new TestingAuthenticationToken(
            "subject", "n/a", AuthorityUtils.createAuthorityList(authorities));
    Object target = OBJENESIS.newInstance(GameItemController.class);
    return MANAGER
        .authorize(
            () -> authentication,
            new SimpleMethodInvocation(target, getGameItems(), null, orderable, 0, 20, "name"))
        .isGranted();
  }

  @Test
  @DisplayName("the full catalogue reads the bookable query and keeps the id tiebreaker")
  void fullCatalogueReadsTheBookableQuery() {
    InventoryGameItemReferenceDto ref =
        new InventoryGameItemReferenceDto(UUID.randomUUID(), "Quantum Drive", "RSI", "GENERIC");
    when(inventoryItemCatalogService.findBookableItems(eq("drive"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(ref), PageRequest.of(0, 20), 1));

    PageResponse<InventoryGameItemReferenceDto> result =
        controller.getGameItems("drive", false, 0, 20, "name,asc;id,asc");

    assertThat(result.content()).containsExactly(ref);
    assertThat(result.totalElements()).isEqualTo(1L);
    ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
    verify(inventoryItemCatalogService).findBookableItems(eq("drive"), pageable.capture());
    assertThat(pageable.getValue().getSort().getOrderFor("name")).isNotNull();
    assertThat(pageable.getValue().getSort().getOrderFor("id")).isNotNull();
    verify(inventoryItemCatalogService, never()).findOrderableItems(any(), any());
  }

  @Test
  @DisplayName("orderable=true reads the narrower orderable query only")
  void orderableReadsTheOrderableQuery() {
    InventoryGameItemReferenceDto ref =
        new InventoryGameItemReferenceDto(UUID.randomUUID(), "Cirrus Scope", "Behring", "WEAPON");
    when(inventoryItemCatalogService.findOrderableItems(eq("cirrus"), any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(ref), PageRequest.of(0, 20), 1));

    PageResponse<InventoryGameItemReferenceDto> result =
        controller.getGameItems("cirrus", true, 0, 20, "name,asc");

    assertThat(result.content()).containsExactly(ref);
    verify(inventoryItemCatalogService, never()).findBookableItems(any(), any());
  }

  @Test
  @DisplayName("the blueprint list and the derivation delegate to the job-order item service")
  void blueprintsAndDerivationDelegate() {
    UUID gameItemId = UUID.randomUUID();
    UUID blueprintId = UUID.randomUUID();
    List<BlueprintReferenceDto> blueprints =
        List.of(new BlueprintReferenceDto(blueprintId, "Scope", "key"));
    ItemDerivationDto derivation = org.mockito.Mockito.mock(ItemDerivationDto.class);
    when(jobOrderItemService.blueprintsForItem(gameItemId)).thenReturn(blueprints);
    when(jobOrderItemService.deriveForPreview(blueprintId, 3)).thenReturn(derivation);

    assertThat(controller.getBlueprintsForItem(gameItemId)).isSameAs(blueprints);
    assertThat(controller.getBlueprintDerivation(blueprintId, 3)).isSameAs(derivation);
  }

  @Test
  @DisplayName("the full catalogue admits exactly the four roles of the former inventory rule")
  void fullCatalogueKeepsTheInventoryRoles() {
    for (String role : List.of(Roles.ADMIN, Roles.OFFICER, Roles.LOGISTICIAN, Roles.KRT_MEMBER)) {
      assertThat(granted(false, Roles.authority(role))).as(role).isTrue();
    }
    for (String role : List.of(Roles.BANK_EMPLOYEE, Roles.BANK_MANAGEMENT, Roles.MISSION_MANAGER)) {
      assertThat(granted(false, Roles.authority(role))).as(role).isFalse();
    }
    assertThat(granted(false, Roles.NO_ROLE_MARKER)).as("NO_ROLE").isFalse();
    assertThat(granted(false)).as("no authority").isFalse();
  }

  @Test
  @DisplayName("the orderable subset stays open to any login, as the former order catalogue was")
  void orderableSubsetStaysOpenToAnyLogin() {
    for (String role :
        List.of(
            Roles.ADMIN,
            Roles.KRT_MEMBER,
            Roles.BANK_EMPLOYEE,
            Roles.BANK_MANAGEMENT,
            Roles.MISSION_MANAGER)) {
      assertThat(granted(true, Roles.authority(role))).as(role).isTrue();
    }
  }

  @Test
  @DisplayName("the gate lives on the annotation of the folded read")
  void theGateIsTheSharedConstant() {
    assertThat(getGameItems().getAnnotation(PreAuthorize.class).value())
        .isEqualTo(GameItemController.GAME_ITEM_ACCESS);
  }
}
