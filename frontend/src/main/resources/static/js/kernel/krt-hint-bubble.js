/** The field-hint marker whose bubble is anchored to it in viewport space (REQ-UI-011). */
const HINT = '.scu-hint';

/** Space between the bubble and the viewport edges, in CSS pixels. */
const VIEWPORT_PAD = 6;

/** Space between the bubble and its marker, in CSS pixels; the bubble's arrow fills it. */
const ANCHOR_GAP = 8;

/**
 * Places a hint's bubble next to its marker in viewport coordinates: above the marker when it fits
 * there, below it when it fits only there, otherwise on the roomier side, clamped into the viewport.
 * The arrow stays on the marker's centre however far the bubble is shifted sideways.
 *
 * @param {Element} hint the `.scu-hint` marker
 */
function placeBubble(hint) {
    const bubble = hint.querySelector('.scu-hint__bubble');
    if (!(bubble instanceof HTMLElement)) return;
    const anchor = hint.getBoundingClientRect();
    const width = bubble.offsetWidth;
    const height = bubble.offsetHeight;
    const viewportWidth = document.documentElement.clientWidth;
    const viewportHeight = document.documentElement.clientHeight;
    const centre = anchor.left + anchor.width / 2;
    const maxLeft = Math.max(VIEWPORT_PAD, viewportWidth - VIEWPORT_PAD - width);
    const left = Math.min(Math.max(centre - width / 2, VIEWPORT_PAD), maxLeft);
    const roomAbove = anchor.top - ANCHOR_GAP - VIEWPORT_PAD;
    const roomBelow = viewportHeight - anchor.bottom - ANCHOR_GAP - VIEWPORT_PAD;
    const below = height > roomAbove && (height <= roomBelow || roomBelow > roomAbove);
    const wantedTop = below ? anchor.bottom + ANCHOR_GAP : anchor.top - ANCHOR_GAP - height;
    const maxTop = Math.max(VIEWPORT_PAD, viewportHeight - VIEWPORT_PAD - height);
    const top = Math.min(Math.max(wantedTop, VIEWPORT_PAD), maxTop);
    bubble.classList.toggle('scu-hint__bubble--below', below);
    bubble.style.setProperty('--hint-left', `${Math.round(left)}px`);
    bubble.style.setProperty('--hint-top', `${Math.round(top)}px`);
    bubble.style.setProperty('--hint-arrow-x', `${Math.round(centre - left)}px`);
}

/**
 * Places the bubble of the hint containing `target`, if there is one.
 *
 * @param {EventTarget | null} target the event target
 */
function placeBubbleFrom(target) {
    const hint = target instanceof Element ? target.closest(HINT) : null;
    if (hint) placeBubble(hint);
}

/** Re-anchors every open bubble after a scroll or resize moved its marker. */
function placeOpenBubbles() {
    for (const hint of document.querySelectorAll(`${HINT}:hover, ${HINT}:focus-within`)) {
        placeBubble(hint);
    }
}

/**
 * Anchors every hint bubble to its marker when the marker is hovered or focused, and keeps an open
 * bubble on its marker while the page or any scroll container scrolls or the window resizes.
 */
export function initHintBubbles() {
    document.addEventListener('mouseover', (event) => placeBubbleFrom(event.target));
    document.addEventListener('focusin', (event) => placeBubbleFrom(event.target));
    window.addEventListener('scroll', placeOpenBubbles, { capture: true, passive: true });
    window.addEventListener('resize', placeOpenBubbles, { passive: true });
}
