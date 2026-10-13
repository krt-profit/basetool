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

/**
 * @typedef {object} MissionHooks
 * @property {KrtSectionWriter['write']} write sends one section write (mission-detail.js)
 * @property {KrtSectionWriter['refresh']} refresh re-renders sections (mission-detail.js)
 * @property {KrtSectionWriter['notify']} notify tells peers that sections changed (mission-detail.js)
 * @property {(overlay: Element | null) => void} modalOpen opens a dialog (mission-detail-modals.js)
 * @property {(overlay: Element | null) => void} modalClose closes a dialog (mission-detail-modals.js)
 * @property {(targetId: string, value: string) => void} segSet sets a segmented control (mission-detail-modals.js)
 * @property {(root?: ParentNode | null) => void} localizeDates localizes the dates below a root (mission-detail-tabs.js)
 * @property {((source: Element) => void) | undefined} [openEditCrewModal] opens the crew edit dialog (mission-detail-participants.js)
 * @property {{ sendChanged(sections: string[]): void } | undefined} [presence] the presence tracker (mission-presence.js)
 */

/**
 * The seams between the parts of the mission page (detail, modals, participants, actions, tabs,
 * presence). Each part sets what it offers when it runs and the others read it when they need it, so
 * the parts keep their load order and none imports another. Everything here is set by the time the
 * page is interactive; a part that may run without its counterpart tests the member first.
 */
export const missionHooks = /** @type {MissionHooks} */ (/** @type {unknown} */ ({}));
