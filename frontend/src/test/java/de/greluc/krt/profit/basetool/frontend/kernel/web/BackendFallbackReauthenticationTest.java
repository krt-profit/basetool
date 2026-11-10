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

package de.greluc.krt.profit.basetool.frontend.kernel.web;

import static de.greluc.krt.profit.basetool.frontend.support.ReauthPageMvc.expiredSession;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import de.greluc.krt.profit.basetool.frontend.bank.client.BankBackendClient;
import de.greluc.krt.profit.basetool.frontend.bank.web.AdminBankPageController;
import de.greluc.krt.profit.basetool.frontend.blueprint.client.BlueprintBackendClient;
import de.greluc.krt.profit.basetool.frontend.blueprint.web.AdminPersonalBlueprintsPageController;
import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.client.CatalogueListBackendClient;
import de.greluc.krt.profit.basetool.frontend.catalogue.web.AdminLocationsPageController;
import de.greluc.krt.profit.basetool.frontend.hangar.client.HangarBackendClient;
import de.greluc.krt.profit.basetool.frontend.hangar.web.HangarPageController;
import de.greluc.krt.profit.basetool.frontend.identity.client.IdentityBackendClient;
import de.greluc.krt.profit.basetool.frontend.identity.web.MemberManagementController;
import de.greluc.krt.profit.basetool.frontend.inventory.client.InventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryForm;
import de.greluc.krt.profit.basetool.frontend.inventory.web.InventoryInputPageController;
import de.greluc.krt.profit.basetool.frontend.inventory.web.InventoryListPageController;
import de.greluc.krt.profit.basetool.frontend.inventory.web.InventoryWriteController;
import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.web.JobOrderWriteController;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalogListLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CatalogueCacheEviction;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.QualityTierCatalog;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LayoutContextLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.LogisticianGate;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.MissionManagerGate;
import de.greluc.krt.profit.basetool.frontend.kernel.layout.OrgUnitContextAdvice;
import de.greluc.krt.profit.basetool.frontend.kernel.livesync.LiveSyncLocalBus;
import de.greluc.krt.profit.basetool.frontend.kernel.security.FrontendAuthHelperService;
import de.greluc.krt.profit.basetool.frontend.mission.client.MissionBackendClient;
import de.greluc.krt.profit.basetool.frontend.mission.web.MissionFinancePageController;
import de.greluc.krt.profit.basetool.frontend.mission.web.MissionPageController;
import de.greluc.krt.profit.basetool.frontend.notification.client.NotificationBackendClient;
import de.greluc.krt.profit.basetool.frontend.notification.web.NotificationPreferenceProxyController;
import de.greluc.krt.profit.basetool.frontend.operation.client.OperationBackendClient;
import de.greluc.krt.profit.basetool.frontend.operation.web.OperationPageController;
import de.greluc.krt.profit.basetool.frontend.orgunit.client.OrgUnitBackendClient;
import de.greluc.krt.profit.basetool.frontend.orgunit.web.AdminSpecialCommandsPageController;
import de.greluc.krt.profit.basetool.frontend.personalinventory.client.PersonalInventoryBackendClient;
import de.greluc.krt.profit.basetool.frontend.personalinventory.web.AdminPersonalInventoryPageController;
import de.greluc.krt.profit.basetool.frontend.refinery.client.RefineryBackendClient;
import de.greluc.krt.profit.basetool.frontend.refinery.web.RefineryOrderWriteController;
import de.greluc.krt.profit.basetool.frontend.settings.client.SettingsBackendClient;
import de.greluc.krt.profit.basetool.frontend.settings.web.AdminSettingsPageController;
import de.greluc.krt.profit.basetool.frontend.supply.client.SupplyBackendClient;
import de.greluc.krt.profit.basetool.frontend.supply.web.SupplyFeatureState;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.MessageSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.ui.ConcurrentModel;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

/** Exercises actual domain fallbacks with session loss and ordinary backend failure. */
class BackendFallbackReauthenticationTest {

  private static final UUID ID = UUID.fromString("ace5ee48-52e7-4f29-afd8-5fe2c92695da");

  @ParameterizedTest
  @EnumSource(Domain.class)
  void directAndNestedSessionLossEscapesWithoutPublishingFallback(Domain domain) {
    for (boolean wrapped : new boolean[] {false, true}) {
      ReauthenticationRequiredException reauth = expiredSession();
      RuntimeException failure =
          wrapped ? new CompletionException(new IllegalStateException(reauth)) : reauth;
      RedirectAttributesModelMap attributes = new RedirectAttributesModelMap();

      assertThatThrownBy(domain.action(failure, attributes)::run).isSameAs(reauth);
      assertThat(attributes.getFlashAttributes()).isEmpty();
    }
  }

  @ParameterizedTest
  @EnumSource(Domain.class)
  void ordinaryBackendFailureKeepsTheLocalFallback(Domain domain) {
    Runnable action =
        domain.action(
            new BackendServiceException("unavailable", null, 503),
            new RedirectAttributesModelMap());

    assertThatCode(action::run).doesNotThrowAnyException();
  }

  private static <T> T rejecting(Class<T> type, RuntimeException failure) {
    return mock(
        type,
        _ -> {
          throw failure;
        });
  }

  private static FrontendAuthHelperService authenticated() {
    FrontendAuthHelperService auth = mock(FrontendAuthHelperService.class);
    when(auth.isAuthenticated()).thenReturn(true);
    return auth;
  }

  private enum Domain {
    BANK,
    BLUEPRINT,
    CATALOGUE,
    HANGAR,
    IDENTITY,
    INVENTORY,
    JOB_ORDER,
    MISSION,
    NOTIFICATION,
    OPERATION,
    ORG_UNIT,
    PERSONAL_INVENTORY,
    REFINERY,
    SETTINGS,
    SUPPLY,
    QUALITY_TIERS,
    LAYOUT,
    SIDEBAR;

    Runnable action(RuntimeException failure, RedirectAttributesModelMap attributes) {
      return switch (this) {
        case BANK ->
            () ->
                new AdminBankPageController(rejecting(BankBackendClient.class, failure))
                    .wipeReset("WIPE", attributes);
        case BLUEPRINT ->
            () ->
                new AdminPersonalBlueprintsPageController(
                        rejecting(BlueprintBackendClient.class, failure))
                    .delete(ID, ID, attributes);
        case CATALOGUE ->
            () ->
                new AdminLocationsPageController(
                        rejecting(CatalogueBackendClient.class, failure),
                        mock(CatalogueCacheEviction.class),
                        mock(CatalogueListBackendClient.class))
                    .toggleHomeLocation(ID, true, attributes);
        case HANGAR ->
            () ->
                new HangarPageController(
                        rejecting(HangarBackendClient.class, failure),
                        new ParallelPageLoader(),
                        mock(CachedCatalogListLoader.class))
                    .deleteShip(ID, attributes);
        case IDENTITY ->
            () ->
                new MemberManagementController(
                        rejecting(IdentityBackendClient.class, failure),
                        mock(MessageSource.class),
                        mock(LiveSyncLocalBus.class))
                    .deleteMember(ID, attributes);
        case INVENTORY ->
            () -> {
              InventoryForm form = new InventoryForm();
              form.setGameItemId(ID);
              form.setLocationId(ID);
              form.setAmount(1.0);
              new InventoryWriteController(
                      rejecting(InventoryBackendClient.class, failure),
                      mock(InventoryInputPageController.class),
                      mock(InventoryListPageController.class))
                  .addInventoryItem(
                      form,
                      new BeanPropertyBindingResult(form, "inventoryForm"),
                      new ConcurrentModel(),
                      attributes);
            };
        case JOB_ORDER ->
            () ->
                new JobOrderWriteController(
                        rejecting(JobOrderBackendClient.class, failure),
                        mock(LogisticianGate.class),
                        new MutationResponseHelper(),
                        mock(LiveSyncLocalBus.class))
                    .unlinkMaterial(ID, ID, attributes);
        case MISSION ->
            () ->
                new MissionFinancePageController(
                        rejecting(MissionBackendClient.class, failure),
                        mock(MissionPageController.class))
                    .deleteFinanceEntry(ID, ID, attributes);
        case NOTIFICATION ->
            () ->
                new NotificationPreferenceProxyController(
                        rejecting(NotificationBackendClient.class, failure))
                    .card(new ConcurrentModel());
        case OPERATION ->
            () ->
                new OperationPageController(
                        rejecting(OperationBackendClient.class, failure),
                        mock(MissionManagerGate.class),
                        mock(MarkdownRenderer.class),
                        new ParallelPageLoader())
                    .deleteOperation(ID, attributes);
        case ORG_UNIT ->
            () ->
                new AdminSpecialCommandsPageController(
                        rejecting(OrgUnitBackendClient.class, failure),
                        mock(CatalogueCacheEviction.class))
                    .deleteSpecialCommand(ID, attributes);
        case PERSONAL_INVENTORY ->
            () ->
                new AdminPersonalInventoryPageController(
                        rejecting(PersonalInventoryBackendClient.class, failure))
                    .delete(ID, ID, attributes);
        case REFINERY ->
            () ->
                new RefineryOrderWriteController(
                        rejecting(RefineryBackendClient.class, failure),
                        mock(LiveSyncLocalBus.class))
                    .deleteOrder(ID, attributes);
        case SETTINGS ->
            () ->
                new AdminSettingsPageController(
                        rejecting(SettingsBackendClient.class, failure),
                        mock(CatalogueCacheEviction.class),
                        mock(MessageSource.class))
                    .updateSettings("30", 0L, "90", 0L, "UP", 0L, "0.5", 0L, attributes);
        case SUPPLY ->
            () -> new SupplyFeatureState(rejecting(SupplyBackendClient.class, failure)).enabled();
        case QUALITY_TIERS ->
            () -> new QualityTierCatalog(rejecting(BackendApiClient.class, failure)).all();
        case LAYOUT ->
            () ->
                new LayoutContextLoader(rejecting(BackendApiClient.class, failure), authenticated())
                    .load(new MockHttpServletRequest());
        case SIDEBAR ->
            () ->
                new OrgUnitContextAdvice(
                        rejecting(BackendApiClient.class, failure),
                        mock(LayoutContextLoader.class),
                        authenticated())
                    .availableSquadrons(new MockHttpServletRequest());
      };
    }
  }
}
