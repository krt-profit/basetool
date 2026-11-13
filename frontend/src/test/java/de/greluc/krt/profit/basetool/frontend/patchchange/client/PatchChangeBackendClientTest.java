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

package de.greluc.krt.profit.basetool.frontend.patchchange.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendClientHarness;
import de.greluc.krt.profit.basetool.frontend.kernel.backend.BackendServiceException;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.CorrectPatchChangeLabelRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeFormDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangeOptions;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePreviewDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchChangePreviewRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchCount;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchCountDto;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.PatchKind;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.TriggerPatchChangeRequest;
import de.greluc.krt.profit.basetool.frontend.patchchange.model.WipeGroup;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Pins the requests {@link PatchChangeBackendClient} sends and the answers it reads. */
class PatchChangeBackendClientTest {

  private static final UUID ID = UUID.fromString("7a0c7a0c-0000-4000-8000-0000000000a4");
  private static final Instant LIVE_AT = Instant.parse("2026-11-14T20:00:00Z");

  /** A rebuild's options as the backend presets them, with one wipe group for a stable body. */
  private static final PatchChangeOptions REBUILD =
      new PatchChangeOptions(Set.of(WipeGroup.CONSUMABLES), true, false, true);

  private static final String OPTIONS_JSON =
      "{\"wipeGroups\":[\"CONSUMABLES\"],\"cancelRefineryOrders\":true,"
          + "\"currencyWipe\":false,\"homeLocationPrompt\":true}";

  private BackendClientHarness backend;
  private PatchChangeBackendClient client;

  @BeforeEach
  void setUp() {
    backend = BackendClientHarness.start();
    client = new PatchChangeBackendClient(backend.backendApiClient());
  }

  @AfterEach
  void tearDown() {
    backend.close();
  }

  @Test
  void theHistoryAndTheFormDefaultsAreRead() {
    backend.answerJson(
        "[{\"id\":\""
            + ID
            + "\",\"version\":2,\"label\":\"4.4\",\"kind\":\"REBUILD\","
            + "\"liveAt\":\"2026-11-14T20:00:00Z\",\"triggeredAt\":\"2026-11-14T21:12:00Z\","
            + "\"triggeredBy\":\"Admin\",\"options\":"
            + OPTIONS_JSON
            + ",\"counts\":[{\"kind\":\"REFINERY_ORDERS_CANCELED\",\"value\":24}]}]");
    backend.answerJson(
        "{\"suggestedLabel\":\"4.5\",\"previousLabel\":\"4.4\","
            + "\"previousLiveAt\":\"2026-11-14T20:00:00Z\",\"presets\":[{\"kind\":\"REBUILD\","
            + "\"options\":"
            + OPTIONS_JSON
            + "},{\"kind\":\"FULL_WIPE\",\"options\":{\"wipeGroups\":[\"CONSUMABLES\","
            + "\"IN_GAME_SHIPS\"],\"cancelRefineryOrders\":true,\"currencyWipe\":false,"
            + "\"homeLocationPrompt\":true}}]}");

    List<PatchChangeDto> history = client.history();
    PatchChangeFormDto form = client.form();

    assertThat(history)
        .containsExactly(
            new PatchChangeDto(
                ID,
                2L,
                "4.4",
                PatchKind.REBUILD,
                LIVE_AT,
                Instant.parse("2026-11-14T21:12:00Z"),
                "Admin",
                REBUILD,
                List.of(new PatchCountDto(PatchCount.REFINERY_ORDERS_CANCELED, 24L))));
    assertThat(form).isNotNull();
    assertThat(form.suggestedLabel()).isEqualTo("4.5");
    assertThat(form.previousLiveAt()).isEqualTo(LIVE_AT);
    assertThat(form.presetFor(PatchKind.REBUILD)).isEqualTo(REBUILD);
    assertThat(form.presetFor(PatchKind.FULL_WIPE).wipeGroups())
        .containsExactlyInAnyOrder(WipeGroup.CONSUMABLES, WipeGroup.IN_GAME_SHIPS);
    backend.expect("GET", "/api/v1/patch-changes/admin");
    backend.expect("GET", "/api/v1/patch-changes/admin/form");
  }

  @Test
  void thePreviewAndTheTriggerPostTheOptions() {
    backend.answerJson(
        "{\"liveAt\":\"2026-11-14T20:00:00Z\","
            + "\"counts\":[{\"kind\":\"REFINERY_ORDERS_CANCELED\",\"value\":3}]}");
    backend.answerJson("{\"id\":\"" + ID + "\",\"version\":0,\"label\":\"4.5\"}");

    PatchChangePreviewDto preview = client.preview(new PatchChangePreviewRequest(LIVE_AT, REBUILD));
    PatchChangeDto triggered =
        client.trigger(
            new TriggerPatchChangeRequest("4.5", PatchKind.REBUILD, null, REBUILD, "4.5"));

    assertThat(preview)
        .isEqualTo(
            new PatchChangePreviewDto(
                LIVE_AT, List.of(new PatchCountDto(PatchCount.REFINERY_ORDERS_CANCELED, 3L))));
    assertThat(triggered).isNotNull();
    assertThat(triggered.label()).isEqualTo("4.5");
    backend.expect(
        "POST",
        "/api/v1/patch-changes/admin/preview",
        "{\"liveAt\":\"2026-11-14T20:00:00Z\",\"options\":" + OPTIONS_JSON + "}");
    backend.expect(
        "POST",
        "/api/v1/patch-changes/admin",
        "{\"label\":\"4.5\",\"kind\":\"REBUILD\",\"liveAt\":null,\"options\":"
            + OPTIONS_JSON
            + ",\"confirmation\":\"4.5\"}");
  }

  @Test
  void aLabelCorrectionPatchesTheLabelWithTheVersion() {
    backend.answerJson("{\"id\":\"" + ID + "\",\"version\":3,\"label\":\"4.4.1\"}");

    PatchChangeDto corrected =
        client.correctLabel(ID, new CorrectPatchChangeLabelRequest("4.4.1", 2L));

    assertThat(corrected).isNotNull();
    assertThat(corrected.version()).isEqualTo(3L);
    backend.expect(
        "PATCH",
        "/api/v1/patch-changes/admin/" + ID + "/label",
        "{\"label\":\"4.4.1\",\"version\":2}");
  }

  @Test
  void aStaleCorrectionFailsWithTheBackendConflict() {
    backend.answerProblem(409, "OPTIMISTIC_LOCK");

    assertThatThrownBy(
            () -> client.correctLabel(ID, new CorrectPatchChangeLabelRequest("4.4.1", 1L)))
        .isInstanceOfSatisfying(
            BackendServiceException.class,
            failure -> assertThat(failure.getStatusCode()).isEqualTo(409));
  }
}
