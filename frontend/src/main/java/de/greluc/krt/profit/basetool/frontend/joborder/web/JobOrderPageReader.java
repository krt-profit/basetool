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

package de.greluc.krt.profit.basetool.frontend.joborder.web;

import de.greluc.krt.profit.basetool.frontend.catalogue.model.MaterialDto;
import de.greluc.krt.profit.basetool.frontend.identity.model.UserDto;
import de.greluc.krt.profit.basetool.frontend.inventory.model.InventoryItemDto;
import de.greluc.krt.profit.basetool.frontend.joborder.client.JobOrderBackendClient;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemBlueprintOwnersDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderItemStockGroupDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderMaterialDto;
import de.greluc.krt.profit.basetool.frontend.joborder.model.JobOrderSettingsDto;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ParallelPageLoader;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.ReauthenticationRequiredException;
import de.greluc.krt.profit.basetool.frontend.kernel.model.BlueprintReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.InventoryGameItemReferenceDto;
import de.greluc.krt.profit.basetool.frontend.kernel.model.PageResponse;
import de.greluc.krt.profit.basetool.frontend.kernel.web.SectionFallback;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.OrgUnitMembershipOptionDto;
import de.greluc.krt.profit.basetool.frontend.orgunit.model.SquadronDto;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Reads the backend data of one job-order page in a single call and returns it as an immutable page
 * record (REQ-FE-036, ADR-0291).
 *
 * <p>The reader owns which backend calls a page needs, which of them run in parallel, which part
 * may fail without failing the page, and the default such a part falls back to. An optional part
 * that fails is logged once through {@code SectionFallback} and shows as its default or as an empty
 * {@link Optional}; a required part fails the read. A lost session always propagates as {@link
 * ReauthenticationRequiredException}. It reads only; writes go through {@link
 * JobOrderBackendClient}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class JobOrderPageReader {

  /** Days after which an open order turns yellow when the settings are unavailable. */
  static final int DEFAULT_AGE_YELLOW_DAYS = 30;

  /** Days after which an open order turns red when the settings are unavailable. */
  static final int DEFAULT_AGE_RED_DAYS = 90;

  private static final String TYPE_ITEM = "ITEM";

  private final JobOrderBackendClient jobOrderClient;
  private final ParallelPageLoader parallelPageLoader;

  /**
   * One optional part of a page. A part that is not {@linkplain #present() present} was not read
   * for this request or its read failed, so its model attribute is left out; a present part keeps
   * its attribute, with the value the backend answered or the part's default.
   *
   * @param present whether the part carries an attribute
   * @param value the part's value, which may be {@code null} when the backend answered none
   * @param <T> the part's value type
   */
  public record Part<T>(boolean present, @Nullable T value) {

    /**
     * Creates a part that carries an attribute.
     *
     * @param value the attribute value, possibly {@code null}
     * @param <T> the value type
     * @return the present part
     */
    public static <T> Part<T> of(@Nullable T value) {
      return new Part<>(true, value);
    }

    /**
     * Creates a part without an attribute.
     *
     * @param <T> the value type
     * @return the absent part
     */
    public static <T> Part<T> absent() {
      return new Part<>(false, null);
    }
  }

  /**
   * What the order list needs to ask for.
   *
   * @param ownScope whether the requester-redacted own-orders list is read
   * @param toProcessScope whether the queue of the caller's own units is read
   * @param page the zero-based page index
   * @param size the page size
   * @param statuses the resolved status filter
   * @param squadronIds the selected squadron ids
   * @param withSquadronFilter whether the active squadrons of the filter picker are read
   */
  public record OrderListQuery(
      boolean ownScope,
      boolean toProcessScope,
      int page,
      int size,
      @NotNull List<String> statuses,
      @NotNull List<UUID> squadronIds,
      boolean withSquadronFilter) {}

  /**
   * Everything the order list page renders.
   *
   * @param ordersAvailable whether the order list itself could be read; {@code false} shows the
   *     load-error banner
   * @param orders the orders of the page, empty when unavailable
   * @param ordersPage the page envelope; empty when unavailable or absent from the answer
   * @param ageYellowDays the yellow aging threshold, the default when the settings are unavailable
   * @param ageRedDays the red aging threshold, the default when the settings are unavailable
   * @param activeSquadrons the active squadrons of the filter picker, name-sorted; empty when not
   *     asked for or unavailable
   */
  public record OrderListPage(
      boolean ordersAvailable,
      @NotNull List<JobOrderDto> orders,
      @NotNull Optional<PageResponse<JobOrderDto>> ordersPage,
      int ageYellowDays,
      int ageRedDays,
      @NotNull List<SquadronDto> activeSquadrons) {

    private OrderListPage withActiveSquadrons(@NotNull List<SquadronDto> squadrons) {
      return new OrderListPage(
          ordersAvailable, orders, ordersPage, ageYellowDays, ageRedDays, squadrons);
    }

    private static OrderListPage unavailable() {
      return new OrderListPage(
          false,
          List.of(),
          Optional.empty(),
          DEFAULT_AGE_YELLOW_DAYS,
          DEFAULT_AGE_RED_DAYS,
          List.of());
    }
  }

  /**
   * Who looks at an order detail page, as far as its reads depend on it.
   *
   * @param authenticated whether the caller is signed in
   * @param tokenUserId the user id carried by the caller's token, or {@code null} when it has none
   * @param logistician whether the caller reaches the Logistician authority
   */
  public record OrderCaller(
      boolean authenticated, @Nullable UUID tokenUserId, boolean logistician) {}

  /**
   * Everything the order detail page renders. An empty {@link Optional} or an absent {@link Part}
   * means the part is not read for this caller, order type or fragment, or that its read failed.
   *
   * @param order the order
   * @param canAssign whether the caller may assign and administer the order
   * @param currentUserId the caller's user id, if it could be resolved
   * @param materials the material catalog, read for a requester or an assigning caller
   * @param squadrons the squadron catalog, read for an assigning caller
   * @param requestingOptions the requesting org-unit options, read for an assigning caller
   * @param actingUser the production modal's acting user, present for an item order's full page;
   *     its value is {@code null} when the lookup failed
   * @param ageYellowDays the yellow aging threshold, the default when the settings are unavailable
   * @param ageRedDays the red aging threshold, the default when the settings are unavailable
   * @param blueprintOwners the blueprint owners of an item order
   * @param itemStockByGameItem the stock groups of an item order's lines keyed by game item
   * @param orphanedInventory the stock linked to the order but needed by none of its materials
   */
  public record OrderDetailPage(
      @NotNull JobOrderDto order,
      boolean canAssign,
      @NotNull Optional<UUID> currentUserId,
      @NotNull Optional<List<MaterialDto>> materials,
      @NotNull Optional<List<SquadronDto>> squadrons,
      @NotNull Optional<List<OrgUnitMembershipOptionDto>> requestingOptions,
      @NotNull Part<UserDto> actingUser,
      int ageYellowDays,
      int ageRedDays,
      @NotNull Part<JobOrderItemBlueprintOwnersDto> blueprintOwners,
      @NotNull Part<Map<UUID, JobOrderItemStockGroupDto>> itemStockByGameItem,
      @NotNull Part<List<InventoryItemDto>> orphanedInventory) {}

  /**
   * Everything the create form renders.
   *
   * @param materials the material catalog, empty when unavailable
   * @param hasOrderableItems whether an orderable item exists; {@code true} when the probe failed
   * @param squadrons the squadron catalog, empty when unavailable
   * @param requestingOptions the requesting org-unit options, empty when unavailable
   */
  public record OrderCreatePage(
      @NotNull List<MaterialDto> materials,
      boolean hasOrderableItems,
      @NotNull List<SquadronDto> squadrons,
      @NotNull List<OrgUnitMembershipOptionDto> requestingOptions) {}

  /** Why the item-order edit form is, or is not, offered. */
  public enum ItemEditAccess {
    /** The order is an item order without item handovers and can be edited. */
    EDITABLE,
    /** The order could not be read. */
    ORDER_UNAVAILABLE,
    /** The order is missing or no item order. */
    NOT_AN_ITEM_ORDER,
    /** The order has an item handover and is locked against editing. */
    HAS_ITEM_HANDOVERS
  }

  /**
   * Everything the item-order edit form renders. The catalogs are only read when the order is
   * {@link ItemEditAccess#EDITABLE}.
   *
   * @param access whether the form is offered
   * @param order the order, present when {@code access} is not {@link
   *     ItemEditAccess#ORDER_UNAVAILABLE}
   * @param materials the material catalog, empty when not read or unavailable
   * @param hasOrderableItems whether an orderable item exists; {@code true} when the probe failed
   * @param squadrons the squadron catalog, empty when not read or unavailable
   * @param requestingOptions the requesting org-unit options, empty when not read or unavailable
   */
  public record ItemEditPage(
      @NotNull ItemEditAccess access,
      @NotNull Optional<JobOrderDto> order,
      @NotNull List<MaterialDto> materials,
      boolean hasOrderableItems,
      @NotNull List<SquadronDto> squadrons,
      @NotNull List<OrgUnitMembershipOptionDto> requestingOptions) {

    private static ItemEditPage blocked(
        @NotNull ItemEditAccess access, @NotNull Optional<JobOrderDto> order) {
      return new ItemEditPage(access, order, List.of(), true, List.of(), List.of());
    }
  }

  private record DetailCatalogs(
      Optional<List<MaterialDto>> materials,
      Optional<List<SquadronDto>> squadrons,
      Optional<List<OrgUnitMembershipOptionDto>> requestingOptions) {}

  private record OrderLookup(boolean available, @Nullable JobOrderDto order) {}

  private static final OrderLookup ORDER_UNAVAILABLE = new OrderLookup(false, null);

  /**
   * Reads the order list page: one page of orders with the aging thresholds and, when asked, the
   * active squadrons of the filter picker.
   *
   * @param query the scope, page, filters and whether the squadron picker is needed
   * @return the list page; its orders are unavailable when the list read failed
   * @throws ReauthenticationRequiredException when the session is lost
   */
  @NotNull
  public OrderListPage readOrderList(@NotNull OrderListQuery query) {
    OrderListPage list =
        SectionFallback.load(
            log, "job-order-list", () -> loadOrderList(query), OrderListPage.unavailable());
    return query.withSquadronFilter() ? list.withActiveSquadrons(activeSquadrons()) : list;
  }

  /**
   * Reads the order detail page for the given caller.
   *
   * @param id the order id
   * @param caller who looks at the page
   * @param fragment the requested fragment, or {@code null} for the full page
   * @return the detail page
   * @throws ReauthenticationRequiredException when the session is lost
   * @throws RuntimeException when the order itself cannot be read
   */
  @NotNull
  public OrderDetailPage readOrderDetail(
      @NotNull UUID id, @NotNull OrderCaller caller, @Nullable String fragment) {
    JobOrderDto order = jobOrderClient.order(id);
    boolean requesterView = order.redacted();
    boolean canAssign = !requesterView && caller.logistician();
    boolean itemOrder = TYPE_ITEM.equals(order.type());
    Optional<UUID> currentUserId = currentUserId(caller);
    DetailCatalogs catalogs = detailCatalogs(requesterView, canAssign);
    Part<UserDto> actingUser =
        canAssign && itemOrder && fragment == null ? actingUser() : Part.absent();
    JobOrderSettingsDto settings = settings();
    return new OrderDetailPage(
        order,
        canAssign,
        currentUserId,
        catalogs.materials(),
        catalogs.squadrons(),
        catalogs.requestingOptions(),
        actingUser,
        yellowDays(settings),
        redDays(settings),
        itemOrder && (fragment == null || "blueprint-owners".equalsIgnoreCase(fragment))
            ? blueprintOwners(id)
            : Part.absent(),
        itemOrder && !requesterView && (fragment == null || "items".equalsIgnoreCase(fragment))
            ? itemStock(id)
            : Part.absent(),
        fragment == null ? orphanedInventory(id) : Part.absent());
  }

  private DetailCatalogs detailCatalogs(boolean requesterView, boolean canAssign) {
    if (canAssign) {
      CompletableFuture<List<MaterialDto>> materialsFuture =
          parallelPageLoader.loadAsync(this::materials);
      CompletableFuture<List<SquadronDto>> squadronsFuture =
          parallelPageLoader.loadAsync(this::squadrons);
      CompletableFuture<List<OrgUnitMembershipOptionDto>> optionsFuture =
          parallelPageLoader.loadAsync(this::requestingOptions);
      join(materialsFuture, squadronsFuture, optionsFuture);
      return new DetailCatalogs(
          Optional.of(materialsFuture.join()),
          Optional.of(squadronsFuture.join()),
          Optional.of(optionsFuture.join()));
    }
    return new DetailCatalogs(
        requesterView ? Optional.of(materials()) : Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  private Part<UserDto> actingUser() {
    return Part.of(
        SectionFallback.load(log, "job-order-acting-user", jobOrderClient::currentUser, null));
  }

  private Part<JobOrderItemBlueprintOwnersDto> blueprintOwners(@NotNull UUID id) {
    return SectionFallback.load(
        log,
        "job-order-blueprint-owners",
        () -> Part.of(jobOrderClient.itemBlueprintOwners(id)),
        Part.absent());
  }

  private Part<Map<UUID, JobOrderItemStockGroupDto>> itemStock(@NotNull UUID id) {
    return SectionFallback.load(
        log, "job-order-item-stock", () -> Part.of(stockByGameItem(id)), Part.absent());
  }

  private Part<List<InventoryItemDto>> orphanedInventory(@NotNull UUID id) {
    return SectionFallback.load(
        log,
        "job-order-orphaned-inventory",
        () -> Part.of(jobOrderClient.orphanedInventory(id)),
        Part.absent());
  }

  /**
   * Reads the create form: the material catalog, the orderable-item probe, the squadrons and the
   * owner-picker options.
   *
   * @return the create page with every part defaulted when it is unavailable
   * @throws ReauthenticationRequiredException when the session is lost
   */
  @NotNull
  public OrderCreatePage readOrderCreate() {
    List<MaterialDto> materials = materials();
    boolean hasOrderableItems = hasOrderableItems();
    List<SquadronDto> squadrons = squadrons();
    return new OrderCreatePage(materials, hasOrderableItems, squadrons, requestingOptions());
  }

  /**
   * Reads the item-order edit form. The catalogs are skipped when the order is not editable.
   *
   * @param id the item-order id
   * @return the edit page; {@link ItemEditPage#access()} says whether the form is offered
   * @throws ReauthenticationRequiredException when the session is lost
   */
  @NotNull
  public ItemEditPage readItemEdit(@NotNull UUID id) {
    OrderLookup lookup =
        SectionFallback.load(
            log,
            "job-order-item-edit",
            () -> new OrderLookup(true, jobOrderClient.order(id)),
            ORDER_UNAVAILABLE);
    if (!lookup.available()) {
      return ItemEditPage.blocked(ItemEditAccess.ORDER_UNAVAILABLE, Optional.empty());
    }
    JobOrderDto order = lookup.order();
    if (order == null || !TYPE_ITEM.equals(order.type())) {
      return ItemEditPage.blocked(ItemEditAccess.NOT_AN_ITEM_ORDER, Optional.ofNullable(order));
    }
    if (order.itemHandovers() != null && !order.itemHandovers().isEmpty()) {
      return ItemEditPage.blocked(ItemEditAccess.HAS_ITEM_HANDOVERS, Optional.of(order));
    }
    List<MaterialDto> materials = materials();
    boolean hasOrderableItems = hasOrderableItems();
    List<SquadronDto> squadrons = squadrons();
    return new ItemEditPage(
        ItemEditAccess.EDITABLE,
        Optional.of(order),
        materials,
        hasOrderableItems,
        squadrons,
        requestingOptions());
  }

  /**
   * Reads the blueprints that produce an orderable item for the create form's picker.
   *
   * @param gameItemId the orderable item
   * @return the blueprint references, empty when the read failed
   * @throws ReauthenticationRequiredException when the session is lost
   */
  @NotNull
  public List<BlueprintReferenceDto> readItemBlueprints(@NotNull UUID gameItemId) {
    List<BlueprintReferenceDto> blueprints =
        SectionFallback.load(
            log,
            "job-order-item-blueprints",
            () -> jobOrderClient.itemBlueprints(gameItemId),
            null);
    return blueprints != null ? blueprints : List.of();
  }

  private OrderListPage loadOrderList(@NotNull OrderListQuery query) {
    PageResponse<JobOrderDto> p =
        jobOrderClient.orders(
            query.ownScope(),
            query.toProcessScope(),
            query.page(),
            query.size(),
            query.statuses(),
            query.squadronIds());
    List<JobOrderDto> orders = new ArrayList<>();
    if (p != null && p.content() != null) {
      orders = new ArrayList<>(p.content());
      logStock(orders);
    }
    JobOrderSettingsDto settings = settings();
    return new OrderListPage(
        true, orders, Optional.ofNullable(p), yellowDays(settings), redDays(settings), List.of());
  }

  private static void logStock(@NotNull List<JobOrderDto> orders) {
    if (!log.isDebugEnabled()) {
      return;
    }
    for (JobOrderDto order : orders) {
      for (JobOrderMaterialDto mat : order.materials()) {
        log.debug(
            "Received stock for job order #{} ({}): {}/{} (material: {})",
            order.displayId(),
            order.id(),
            mat.currentStock(),
            mat.amount(),
            mat.material().name());
      }
    }
  }

  private static int yellowDays(@Nullable JobOrderSettingsDto settings) {
    return settings != null ? settings.ageYellowDays() : DEFAULT_AGE_YELLOW_DAYS;
  }

  private static int redDays(@Nullable JobOrderSettingsDto settings) {
    return settings != null ? settings.ageRedDays() : DEFAULT_AGE_RED_DAYS;
  }

  @Nullable
  private JobOrderSettingsDto settings() {
    return SectionFallback.load(log, "job-order-age-settings", jobOrderClient::settings, null);
  }

  private Optional<UUID> currentUserId(@NotNull OrderCaller caller) {
    if (!caller.authenticated()) {
      return Optional.empty();
    }
    if (caller.tokenUserId() != null) {
      return Optional.of(caller.tokenUserId());
    }
    UserDto me =
        SectionFallback.load(log, "job-order-current-user", jobOrderClient::currentUser, null);
    return me != null ? Optional.ofNullable(me.id()) : Optional.empty();
  }

  @NotNull
  private List<MaterialDto> materials() {
    List<MaterialDto> list =
        SectionFallback.load(log, "job-order-materials", jobOrderClient::jobOrderMaterials, null);
    return list != null ? new ArrayList<>(list) : new ArrayList<>();
  }

  @NotNull
  private List<SquadronDto> squadrons() {
    PageResponse<SquadronDto> p =
        SectionFallback.load(log, "job-order-squadrons", jobOrderClient::squadrons, null);
    return p != null && p.content() != null ? new ArrayList<>(p.content()) : new ArrayList<>();
  }

  @NotNull
  private List<SquadronDto> activeSquadrons() {
    return squadrons().stream()
        .filter(s -> Boolean.TRUE.equals(s.active()))
        .sorted(
            Comparator.comparing(
                s -> s.name() != null ? s.name() : "", String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  @NotNull
  private List<OrgUnitMembershipOptionDto> requestingOptions() {
    List<OrgUnitMembershipOptionDto> options =
        SectionFallback.load(
            log, "job-order-requesting-org-units", jobOrderClient::activeOrgUnitsAllKinds, null);
    return options != null ? options : activeOrgUnits();
  }

  @NotNull
  private List<OrgUnitMembershipOptionDto> activeOrgUnits() {
    List<OrgUnitMembershipOptionDto> options =
        SectionFallback.load(
            log, "job-order-active-org-units", jobOrderClient::activeOrgUnits, null);
    return options != null ? options : List.of();
  }

  private boolean hasOrderableItems() {
    return SectionFallback.load(
        log,
        "job-order-orderable-item-probe",
        () -> {
          PageResponse<InventoryGameItemReferenceDto> page = jobOrderClient.orderableItemProbe();
          return page == null || page.content() == null || !page.content().isEmpty();
        },
        true);
  }

  @NotNull
  private Map<UUID, JobOrderItemStockGroupDto> stockByGameItem(@NotNull UUID id) {
    List<JobOrderItemStockGroupDto> stock = jobOrderClient.itemStock(id);
    Map<UUID, JobOrderItemStockGroupDto> byGameItem = new LinkedHashMap<>();
    if (stock != null) {
      for (JobOrderItemStockGroupDto group : stock) {
        if (group.gameItem() != null && group.gameItem().id() != null) {
          byGameItem.put(group.gameItem().id(), group);
        }
      }
    }
    return byGameItem;
  }

  private static void join(@NotNull CompletableFuture<?>... futures) {
    try {
      CompletableFuture.allOf(futures).join();
    } catch (CompletionException e) {
      if (e.getCause() instanceof ReauthenticationRequiredException reauth) {
        throw reauth;
      }
      throw e;
    }
  }
}
