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

package de.greluc.krt.profit.basetool.frontend.purchasing;

import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.CREATED_AT;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.deal;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.dealPartner;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.decidedDelivery;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.delegate;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.page;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.partner;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.recordedDelivery;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.settlement;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.state;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.tier;
import static de.greluc.krt.profit.basetool.frontend.purchasing.PurchasingFixtures.tranche;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyClass;
import static de.greluc.krt.profit.basetool.frontend.support.ResponseTypeMatchers.anyTypeRef;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendApiClient;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.CachedCatalog;
import de.greluc.krt.profit.basetool.frontend.purchasing.client.PurchasingBackendClient;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DealDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.DelegateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PartnerDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PauseStateDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.PurchasingReportDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.ReportMaterialDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.ReportMonthDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.ReportTotalsDto;
import de.greluc.krt.profit.basetool.frontend.purchasing.model.UnitPause;
import de.greluc.krt.profit.basetool.frontend.purchasing.web.PurchasingFeatureState;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.invocation.InvocationOnMock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** Render coverage of the purchasing fragments: chips, money and the flag-gated action buttons. */
@SpringBootTest
@WithMockUser(roles = "KRT_MEMBER", username = "test-user-123")
class PurchasingTemplateRenderTest {

  private static final String ACCEPT = "purchasing-accept-delivery";

  @Autowired private WebApplicationContext context;

  @Autowired private PurchasingFeatureState featureState;

  private MockMvc mockMvc;

  @MockitoBean private BackendApiClient backendApiClient;

  private final Map<String, Object> answers = new HashMap<>();

  @Autowired private PurchasingBackendClient purchasingClient;

  @MockitoBean
  private org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
      clientRegistrationRepository;

  @BeforeEach
  void setup() {
    featureState.evict();
    mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    when(backendApiClient.getCached(any(CachedCatalog.class), anyTypeRef()))
        .thenReturn(Collections.emptyList());
    givenState(false, false);
    answers.clear();
    answers.put("pause", new PauseStateDto(false, null, null, List.of()));
    when(backendApiClient.get(anyString(), anyClass()))
        .thenAnswer(invocation -> answer(invocation.getArgument(0), null));
    when(backendApiClient.get(anyString(), anyClass(), any(Object[].class)))
        .thenAnswer(invocation -> answer(invocation.getArgument(0), lastArgument(invocation)));
    when(backendApiClient.get(anyString(), anyTypeRef()))
        .thenAnswer(invocation -> answer(invocation.getArgument(0), null));
    when(backendApiClient.get(anyString(), anyTypeRef(), any(Object[].class)))
        .thenAnswer(invocation -> answer(invocation.getArgument(0), lastArgument(invocation)));
    givenState(false, false);
  }

  private static Object lastArgument(InvocationOnMock invocation) {
    Object[] arguments = invocation.getArguments();
    return arguments[arguments.length - 1];
  }

  private Object answer(String uri, Object variable) {
    if (uri.startsWith("/api/v1/purchasing/state")) {
      return answers.get("state");
    }
    if (uri.startsWith("/api/v1/purchasing/pause")) {
      return answers.get("pause");
    }
    if (uri.startsWith("/api/v1/purchasing/report")) {
      return answers.get("report");
    }
    if (uri.startsWith("/api/v1/purchasing/deals/{id}")) {
      return answers.get("deal:" + variable);
    }
    if (uri.startsWith("/api/v1/purchasing/deals")) {
      return answers.get("deals");
    }
    if (uri.startsWith("/api/v1/purchasing/partners")) {
      return answers.get("partners");
    }
    if (uri.startsWith("/api/v1/purchasing/delegates")) {
      return answers.get("delegates");
    }
    return null;
  }

  @AfterEach
  void tearDown() {
    featureState.evict();
  }

  private void givenState(boolean delegate, boolean governing) {
    answers.put("state", state(delegate, governing, false));
  }

  private void givenDeals(DealDto... deals) {
    answers.put("deals", page(List.of(deals)));
  }

  private void givenPartners(PartnerDto... partners) {
    answers.put("partners", page(List.of(partners)));
  }

  private String render(String path) throws Exception {
    return mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andReturn()
        .getResponse()
        .getContentAsString(StandardCharsets.UTF_8);
  }

  private String renderDeal(DealDto deal) throws Exception {
    answers.put("deal:" + deal.id(), deal);
    return render("/purchasing/deals/" + deal.id() + "?fragment=detail");
  }

  private static int count(String html, String needle) {
    int found = 0;
    int from = html.indexOf(needle);
    while (from >= 0) {
      found++;
      from = html.indexOf(needle, from + needle.length());
    }
    return found;
  }

  private static String rowOf(String html, String attribute) {
    Matcher matcher =
        Pattern.compile("<tr[^>]*" + Pattern.quote(attribute) + "[^>]*>.*?</tr>", Pattern.DOTALL)
            .matcher(html);
    assertThat(matcher.find()).as("a row carrying %s", attribute).isTrue();
    return matcher.group();
  }

  private static List<String> buttons(String html, String trigger) {
    Matcher matcher =
        Pattern.compile("<button[^>]*data-trigger=\"" + Pattern.quote(trigger) + "\"[^>]*>")
            .matcher(html);
    List<String> found = new ArrayList<>();
    while (matcher.find()) {
      found.add(matcher.group());
    }
    return found;
  }

  private static boolean hasChip(String html, String classes, String text) {
    return Pattern.compile(
            "class=\"" + Pattern.quote(classes) + "\"[^>]*>\\s*" + Pattern.quote(text) + "\\s*<")
        .matcher(html)
        .find();
  }

  @Test
  void dealsResultsShowTheStatusChipsTheOverdueChipAndTheMoney() throws Exception {
    DealDto settled =
        deal().dealNo(7).status("SETTLED").total(12_500L).qualityTier(tier(700)).build();
    DealDto late = deal().dealNo(8).status("FUNDED").overdue(true).total(2_000_000L).build();
    givenDeals(settled, late);

    String html = render("/purchasing/deals?fragment=results");

    String settledRow = rowOf(html, "data-deal-id=\"" + settled.id() + "\"");
    assertThat(settledRow)
        .contains("#7")
        .contains("ARGON")
        .contains("Quantanium")
        .contains("Qualität: Hoch")
        .contains("3 von 10 SCU")
        .contains("12.500 aUEC")
        .doesNotContain("purchasing-overdue");
    assertThat(hasChip(settledRow, "chip chip--success", "Abgerechnet")).isTrue();
    String lateRow = rowOf(html, "data-deal-id=\"" + late.id() + "\"");
    assertThat(lateRow)
        .contains("#8")
        .contains("2.000.000 aUEC")
        .contains("data-testid=\"purchasing-overdue\"");
    assertThat(hasChip(lateRow, "chip chip--info", "Finanziert")).isTrue();
    assertThat(hasChip(lateRow, "chip chip--warning", "überfällig")).isTrue();
    assertThat(count(html, "data-testid=\"purchasing-deal-row\"")).isEqualTo(2);
    assertThat(count(html, "data-testid=\"purchasing-overdue\"")).isEqualTo(1);
  }

  @Test
  void dealsResultsWithoutDealsShowNoTable() throws Exception {
    givenDeals();

    String html = render("/purchasing/deals?fragment=results");

    assertThat(html).doesNotContain("purchasing-deal-row").doesNotContain("data-table");
  }

  @Test
  void dealsResultsShowNoBannerWhileNothingIsPaused() throws Exception {
    givenDeals();

    assertThat(render("/purchasing/deals?fragment=results"))
        .doesNotContain("purchasing-paused-banner");
  }

  @Test
  void theBannerNamesEveryPauseButOffersNoResumeToAMemberWithoutGoverningRights() throws Exception {
    UUID unitId = UUID.randomUUID();
    givenDeals();
    answers.put(
        "pause",
        new PauseStateDto(
            true,
            "BUDGET",
            CREATED_AT,
            List.of(new UnitPause(unitId, "ARGON", "INCIDENT", CREATED_AT))));
    givenState(true, false);

    String html = render("/purchasing/deals?fragment=results");

    assertThat(html)
        .contains("data-testid=\"purchasing-paused-banner\"")
        .contains("Der Ankauf ist pausiert: Budget.")
        .contains("ARGON ist pausiert: Zwischenfall.")
        .doesNotContain("data-trigger=\"purchasing-resume\"")
        .doesNotContain("purchasing-resume-global");
  }

  @Test
  void theBannerOffersTheGoverningLeadershipAResumeButtonPerPause() throws Exception {
    UUID unitId = UUID.randomUUID();
    givenDeals();
    answers.put(
        "pause",
        new PauseStateDto(
            true,
            "BUDGET",
            CREATED_AT,
            List.of(new UnitPause(unitId, "ARGON", "INCIDENT", CREATED_AT))));
    givenState(false, true);

    String html = render("/purchasing/deals?fragment=results");

    assertThat(buttons(html, "purchasing-resume")).hasSize(2);
    assertThat(html)
        .contains("data-testid=\"purchasing-resume-global\"")
        .contains("data-unit-id=\"" + unitId + "\"")
        .contains("ARGON fortsetzen");
  }

  @Test
  void aUnitPauseAloneOffersNoGlobalResume() throws Exception {
    UUID unitId = UUID.randomUUID();
    givenDeals();
    answers.put(
        "pause",
        new PauseStateDto(
            false, null, null, List.of(new UnitPause(unitId, "ARGON", "OTHER", CREATED_AT))));
    givenState(false, true);

    String html = render("/purchasing/deals?fragment=results");

    assertThat(buttons(html, "purchasing-resume")).hasSize(1);
    assertThat(html).doesNotContain("purchasing-resume-global").contains("Sonstiger Grund");
  }

  @Test
  void dealDetailOffersEveryActionTheFlagsAllowAndShowsTheMoney() throws Exception {
    DealDto deal =
        deal()
            .everything(true)
            .status("IN_DELIVERY")
            .overdue(true)
            .escortWish(true)
            .riskClass("B")
            .uexReferencePrice(1_400.0)
            .settlementDueAt(CREATED_AT)
            .build();

    String html = renderDeal(deal);

    assertThat(html)
        .contains("data-testid=\"purchasing-edit-deal\"")
        .contains("data-testid=\"purchasing-request-tranche\"")
        .contains("data-testid=\"purchasing-record-delivery\"")
        .contains("data-testid=\"purchasing-close-delivery\"")
        .contains("data-testid=\"purchasing-settle\"")
        .contains("data-testid=\"purchasing-cancel-deal\"")
        .contains("1.250 aUEC")
        .contains("12.500 aUEC")
        .contains("UEX: 1.400 aUEC")
        .contains("5.000 von 12.500 aUEC")
        .contains("Klasse B")
        .contains("01.09.2026");
    assertThat(hasChip(html, "chip chip--info", "In Lieferung")).isTrue();
    assertThat(hasChip(html, "chip chip--warning", "überfällig")).isTrue();
    assertThat(hasChip(html, "chip chip--muted", "Begleitschutz")).isTrue();
  }

  @Test
  void dealDetailOffersNoActionTheFlagsRefuse() throws Exception {
    DealDto deal = deal().everything(false).build();

    String html = renderDeal(deal);

    assertThat(html)
        .contains("data-testid=\"purchasing-deal-overview\"")
        .doesNotContain("purchasing-edit-deal")
        .doesNotContain("purchasing-request-tranche")
        .doesNotContain("purchasing-record-delivery")
        .doesNotContain("purchasing-close-delivery")
        .doesNotContain("data-testid=\"purchasing-settle\"")
        .doesNotContain("purchasing-cancel-deal")
        .doesNotContain("purchasing-overdue")
        .doesNotContain("UEX:");
  }

  @Test
  void eachActionButtonFollowsItsOwnFlagAlone() throws Exception {
    String html = renderDeal(deal().canRecordDelivery(true).canSettle(true).build());

    assertThat(html)
        .contains("data-testid=\"purchasing-record-delivery\"")
        .contains("data-testid=\"purchasing-settle\"")
        .doesNotContain("purchasing-edit-deal")
        .doesNotContain("purchasing-request-tranche")
        .doesNotContain("purchasing-close-delivery")
        .doesNotContain("purchasing-cancel-deal");
  }

  @Test
  void dealDetailShowsAPartnerNoteOnlyWhenTheDtoCarriesIt() throws Exception {
    DealDto onlyConditions = deal().partner(dealPartner("Nur Barzahlung", null)).build();
    DealDto both = deal().partner(dealPartner("Nur Barzahlung", "Zuverlaessig")).build();
    DealDto none = deal().build();

    String conditionsOnly = renderDeal(onlyConditions);
    String bothNotes = renderDeal(both);
    String noNotes = renderDeal(none);

    assertThat(conditionsOnly)
        .contains("<dt>Konditionen</dt>")
        .contains("Nur Barzahlung")
        .doesNotContain("<dt>Ruf</dt>");
    assertThat(bothNotes)
        .contains("<dt>Konditionen</dt>")
        .contains("<dt>Ruf</dt>")
        .contains("Zuverlaessig");
    assertThat(noNotes).doesNotContain("<dt>Konditionen</dt>").doesNotContain("<dt>Ruf</dt>");
  }

  @Test
  void dealDetailOffersTheDecisionOnlyForDeliveriesTheCallerMayAccept() throws Exception {
    UUID open = UUID.randomUUID();
    UUID foreign = UUID.randomUUID();
    DealDto deal =
        deal()
            .qualityTier(tier(700))
            .deliveries(List.of(recordedDelivery(open, true), recordedDelivery(foreign, false)))
            .build();

    String html = renderDeal(deal);

    List<String> accept = buttons(html, ACCEPT);
    assertThat(accept).hasSize(1);
    assertThat(accept.getFirst())
        .contains("data-delivery-id=\"" + open + "\"")
        .contains("data-min-quality=\"700\"")
        .contains("data-material=\"true\"");
    assertThat(html).contains("data-delivery-id=\"" + foreign + "\"");
    assertThat(rowOf(html, "data-delivery-id=\"" + foreign + "\"")).doesNotContain(ACCEPT);
    assertThat(rowOf(html, "data-delivery-id=\"" + open + "\"")).contains(ACCEPT);
  }

  @Test
  void dealDetailOffersNoDecisionWhenNoDeliveryMayBeAccepted() throws Exception {
    DealDto deal =
        deal()
            .deliveries(
                List.of(
                    recordedDelivery(UUID.randomUUID(), false), decidedDelivery(UUID.randomUUID())))
            .build();

    assertThat(buttons(renderDeal(deal), ACCEPT)).isEmpty();
  }

  @Test
  void dealDetailListsTranchesAndADecidedDeliveryWithItsOutcome() throws Exception {
    UUID decided = UUID.randomUUID();
    DealDto deal =
        deal()
            .tranches(
                List.of(
                    tranche("ADVANCE", 5_000L, "CONFIRMED"), tranche("FINAL", 1_000L, "REQUESTED")))
            .deliveries(List.of(decidedDelivery(decided)))
            .build();

    String html = renderDeal(deal);

    assertThat(count(html, "data-testid=\"purchasing-tranche-row\"")).isEqualTo(2);
    assertThat(html)
        .contains("5.000 aUEC")
        .contains("1.000 aUEC")
        .contains("Vorkasse")
        .contains("Schlusszahlung");
    assertThat(hasChip(html, "chip chip--success", "Ausgezahlt")).isTrue();
    assertThat(hasChip(html, "chip chip--info", "Angefordert")).isTrue();
    String row = rowOf(html, "data-delivery-id=\"" + decided + "\"");
    assertThat(row)
        .contains("4 angenommen, 1 abgelehnt")
        .contains("Beschädigt")
        .contains("Qualität 700");
    assertThat(hasChip(row, "chip chip--success", "Angenommen")).isTrue();
  }

  @Test
  void dealDetailWithoutTranchesAndDeliveriesShowsTheEmptyHints() throws Exception {
    String html = renderDeal(deal().build());

    assertThat(html)
        .contains("Noch keine Mittel angefordert.")
        .contains("Noch keine Lieferung.")
        .doesNotContain("purchasing-tranche-row")
        .doesNotContain("purchasing-delivery-row")
        .doesNotContain("data-testid=\"purchasing-settlement\"");
  }

  @Test
  void dealDetailShowsTheSettlementAndTheWaitForTheBankWhileTheRemainderIsOpen() throws Exception {
    DealDto waiting = deal().settlement(settlement(null, UUID.randomUUID())).build();
    DealDto done = deal().settlement(settlement(CREATED_AT, UUID.randomUUID())).build();

    String waitingHtml = renderDeal(waiting);
    String doneHtml = renderDeal(done);

    assertThat(waitingHtml)
        .contains("data-testid=\"purchasing-settlement\"")
        .contains("12.500 aUEC")
        .contains("11.000 aUEC")
        .contains("1.500 aUEC")
        .contains("500 aUEC")
        .contains("Betrug")
        .contains("Der Rückfluss wartet auf die Bank.");
    assertThat(doneHtml)
        .contains("data-testid=\"purchasing-settlement\"")
        .doesNotContain("wartet auf die Bank");
  }

  @Test
  void partnersResultsOfferTheRowActionsOfTheirStatus() throws Exception {
    PartnerDto active = partner("Orion", "ORG", "ACTIVE", false, "Nur Barzahlung", "Zuverlaessig");
    PartnerDto blocked = partner("Vega", "ORG", "BLOCKED", false, null, null);
    PartnerDto erased = partner("", "INDIVIDUAL", "BLOCKED", true, null, null);
    givenPartners(active, blocked, erased);
    givenState(false, true);

    String html = render("/purchasing/partners?fragment=results");

    String activeRow = rowOf(html, "data-partner-id=\"" + active.id() + "\"");
    assertThat(activeRow)
        .contains("3 von 4 verlustfrei abgerechnet")
        .contains("data-trigger=\"purchasing-edit-partner\"")
        .contains("data-trigger=\"purchasing-partner-block\"")
        .contains("Nur Barzahlung")
        .contains("Zuverlaessig")
        .doesNotContain("purchasing-partner-unblock")
        .doesNotContain("purchasing-partner-erase");
    assertThat(hasChip(activeRow, "chip chip--success", "Aktiv")).isTrue();
    String blockedRow = rowOf(html, "data-partner-id=\"" + blocked.id() + "\"");
    assertThat(blockedRow)
        .contains("data-trigger=\"purchasing-partner-unblock\"")
        .contains("data-trigger=\"purchasing-edit-partner\"")
        .doesNotContain("purchasing-partner-block\"")
        .doesNotContain("purchasing-partner-erase");
    assertThat(hasChip(blockedRow, "chip chip--danger", "Gesperrt")).isTrue();
    String erasedRow = rowOf(html, "data-partner-id=\"" + erased.id() + "\"");
    assertThat(erasedRow)
        .doesNotContain("purchasing-edit-partner")
        .doesNotContain("purchasing-partner-block")
        .doesNotContain("purchasing-partner-unblock")
        .doesNotContain("purchasing-partner-erase");
  }

  @Test
  void partnersResultsOfferTheErasureOfAnIndividualOnlyToTheGoverningLeadership() throws Exception {
    PartnerDto individual = partner("Maverick", "INDIVIDUAL", "ACTIVE", false, null, null);
    PartnerDto org = partner("Orion", "ORG", "ACTIVE", false, null, null);
    givenPartners(individual, org);
    givenState(true, true);

    String governing = render("/purchasing/partners?fragment=results");
    givenState(true, false);
    String delegateOnly = render("/purchasing/partners?fragment=results");

    assertThat(buttons(governing, "purchasing-partner-erase")).hasSize(1);
    assertThat(rowOf(governing, "data-partner-id=\"" + individual.id() + "\""))
        .contains("purchasing-partner-erase");
    assertThat(rowOf(governing, "data-partner-id=\"" + org.id() + "\""))
        .doesNotContain("purchasing-partner-erase");
    assertThat(buttons(delegateOnly, "purchasing-partner-erase")).isEmpty();
  }

  @Test
  void rowActionsKeepOneIconActionVisibleAndMoveTheRestIntoTheOverflowMenu() throws Exception {
    PartnerDto active = partner("Orion", "ORG", "ACTIVE", false, null, null);
    DelegateDto standard = delegate("Alpha", null, 50_000L, null);
    givenPartners(active);
    answers.put("delegates", List.of(standard));
    givenState(true, true);

    String partnerRow =
        rowOf(
            render("/purchasing/partners?fragment=results"),
            "data-partner-id=\"" + active.id() + "\"");
    String delegateRow =
        rowOf(
            render("/purchasing/delegates?fragment=results"),
            "data-delegate-id=\"" + standard.id() + "\"");

    assertThat(partnerRow)
        .contains("btn btn-ghost btn-icon")
        .contains("data-overflow-menu")
        .contains("overflow-menu__item--danger");
    assertThat(delegateRow)
        .contains("btn btn-ghost btn-xs")
        .contains("data-overflow-menu")
        .contains("data-trigger=\"purchasing-delegate-revoke\"");
  }

  @Test
  void statusChipsFollowTheEstablishedToneOfTheirState() throws Exception {
    DealDto failed =
        deal()
            .status("FAILED")
            .tranches(
                List.of(tranche("ADVANCE", 100L, "REFUSED"), tranche("FINAL", 100L, "CANCELLED")))
            .build();

    String html = renderDeal(failed);

    assertThat(hasChip(html, "chip chip--danger", "Gescheitert")).isTrue();
    assertThat(hasChip(html, "chip chip--danger", "Abgelehnt")).isTrue();
    assertThat(hasChip(html, "chip chip--muted", "Zurückgezogen")).isTrue();
    assertThat(html)
        .contains("data-testid=\"purchasing-deal-status\"")
        .contains("data-status=\"FAILED\"");
  }

  @Test
  void stackedRowsLabelTheirCellsAndNumbersAreAligned() throws Exception {
    DealDto deal = deal().build();
    givenDeals(deal);

    String row =
        rowOf(render("/purchasing/deals?fragment=results"), "data-deal-id=\"" + deal.id() + "\"");

    assertThat(row)
        .contains("data-label=\"Einheit\"")
        .contains("data-label=\"Menge\"")
        .contains("class=\"num\"")
        .contains("btn btn-ghost btn-xs");
  }

  @Test
  void theReportResultsShowTheTotalsAsKpisAndTheTables() throws Exception {
    answers.put(
        "report",
        new PurchasingReportDto(
            "2026-08",
            "2026-09",
            new ReportTotalsDto(4, 3, 1, 0, 12.5, 9_000L, 1_000L, 8_000L, 500L),
            List.of(new ReportMonthDto("2026-09", 4, 12.5, 9_000L, 1_000L, 8_000L, 500L)),
            List.of(new ReportMaterialDto(UUID.randomUUID(), "Quantanium", 4, 12.5, 640.0, null)),
            List.of()));

    String html = render("/purchasing/report?fragment=results&from=2026-08&to=2026-09");

    assertThat(html)
        .contains("kpi-grid")
        .contains("data-testid=\"purchasing-report-totals\"")
        .contains("data-testid=\"purchasing-report-months\"")
        .contains("data-testid=\"purchasing-report-materials\"")
        .contains("Quantanium")
        .contains("9.000 aUEC")
        .doesNotContain("purchasing-report-needs")
        .doesNotContain("Anwenden");
  }

  @Test
  void theReportResultsOfAPeriodWithoutDealsShowTheEmptyState() throws Exception {
    answers.put(
        "report",
        new PurchasingReportDto(
            "2026-08",
            "2026-09",
            new ReportTotalsDto(0, 0, 0, 0, 0.0, 0L, 0L, 0L, 0L),
            List.of(),
            List.of(),
            List.of()));

    String html = render("/purchasing/report?fragment=results");

    assertThat(html)
        .contains("data-testid=\"empty-state\"")
        .contains("Keine Geschäfte im Zeitraum.");
  }

  @Test
  void partnersResultsWithoutPartnersShowNoTable() throws Exception {
    givenPartners();

    assertThat(render("/purchasing/partners?fragment=results"))
        .doesNotContain("purchasing-partner-row")
        .doesNotContain("data-table");
  }

  @Test
  void delegatesResultsOfferLimitAndRevokeOnlyForAnAppointmentThatIsNotRevoked() throws Exception {
    DelegateDto standard = delegate("Alpha", null, 50_000L, null);
    DelegateDto personal = delegate("Bravo", 75_000L, 75_000L, null);
    DelegateDto revoked = delegate("Charlie", 10_000L, 10_000L, CREATED_AT);
    answers.put("delegates", List.of(standard, personal, revoked));

    String html = render("/purchasing/delegates?fragment=results");

    String standardRow = rowOf(html, "data-delegate-id=\"" + standard.id() + "\"");
    assertThat(standardRow)
        .contains("Alpha")
        .contains("50.000 aUEC")
        .contains("Standard")
        .contains("data-testid=\"purchasing-delegate-limit\"")
        .contains("data-testid=\"purchasing-delegate-revoke\"");
    assertThat(hasChip(standardRow, "chip chip--success", "Aktiv")).isTrue();
    String personalRow = rowOf(html, "data-delegate-id=\"" + personal.id() + "\"");
    assertThat(personalRow).contains("75.000 aUEC").doesNotContain("Standard");
    String revokedRow = rowOf(html, "data-delegate-id=\"" + revoked.id() + "\"");
    assertThat(revokedRow)
        .contains("10.000 aUEC")
        .doesNotContain("purchasing-delegate-limit")
        .doesNotContain("purchasing-delegate-revoke");
    assertThat(hasChip(revokedRow, "chip chip--muted", "Beendet")).isTrue();
  }

  @Test
  void delegatesResultsWithoutAppointmentsShowNoTable() throws Exception {
    answers.put("delegates", List.of());

    assertThat(render("/purchasing/delegates?fragment=results"))
        .doesNotContain("purchasing-delegate-row")
        .doesNotContain("data-table");
  }
}
