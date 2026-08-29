fix(docs): write the tempo versions bare so the image-pin gate cannot erase them

The REQ-OPS-020 example compares resident peaks ACROSS tempo versions, so the
older tag is the point of the sentence. Written as a full image:tag it reads as a
pin, and the gate rewrites a stale pin to today's tag -- which would have turned
the comparison into 3.0.3 against 3.0.3 and deleted the finding while reporting
success. Bare versions carry the same meaning and are not pins.

The gate was right to fire; the sentence was wrong. Its own header warns about
exactly this for ADRs.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
