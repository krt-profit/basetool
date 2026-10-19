/** The horizontally scrolling tab strips that show a scroll cue at a clipped edge. */
const STRIPS = '.tab-nav';

/**
 * Sets `is-clipped-start` and `is-clipped-end` on a strip from its scroll position, so the
 * stylesheet fades exactly the edges behind which tabs are hidden.
 *
 * @param {HTMLElement} strip the tab strip
 */
function markClippedEdges(strip) {
    const max = strip.scrollWidth - strip.clientWidth;
    const left = Math.abs(strip.scrollLeft);
    strip.classList.toggle('is-clipped-start', max > 1 && left > 1);
    strip.classList.toggle('is-clipped-end', max > 1 && left < max - 1);
}

/**
 * Keeps the clipped-edge marks of every tab strip current across scrolling, resizes and tabs that
 * appear or disappear.
 */
export function initTabScrollCues() {
    const strips = document.querySelectorAll(STRIPS);
    if (strips.length === 0) return;
    const observer = new ResizeObserver((entries) => {
        for (const entry of entries) {
            const strip = entry.target.closest(STRIPS);
            if (strip instanceof HTMLElement) markClippedEdges(strip);
        }
    });
    for (const strip of strips) {
        if (!(strip instanceof HTMLElement)) continue;
        strip.addEventListener('scroll', () => markClippedEdges(strip), { passive: true });
        observer.observe(strip);
        for (const tab of strip.children) observer.observe(tab);
        markClippedEdges(strip);
    }
}
