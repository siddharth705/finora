package com.finora.imports.evidence;

/**
 * A physical region on a page, in the same coordinate space as
 * {@code com.finora.imports.pdf.PositionedText} (PDF user-space points, origin/width/height, not
 * x0/x1/y0/y1) -- kept as its own small type here rather than depending on the pdf package's
 * {@code PositionedText}, since correlation only ever needs the geometry, never the text/confidence/
 * source fields that come with it.
 */
public record BoundingBox(float x, float y, float width, float height) {

    public BoundingBox {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("a BoundingBox cannot have negative width or height");
        }
    }

    public float endX() {
        return x + width;
    }

    public float endY() {
        return y + height;
    }

    /**
     * Intersection area over the smaller of the two boxes' areas -- deliberately not a standard
     * intersection-over-union, since an OCR-recognised box for the same text is often a different
     * size than the native box for it (looser or tighter glyph metrics); sizing the ratio against
     * the smaller box avoids penalizing that size mismatch when the boxes genuinely occupy the same
     * region. Placeholder formula, per design §2.2's own note that the exact formula is not fixed
     * yet -- what's fixed is that zero overlap must mean zero, and full containment must mean 1.0.
     *
     * @return 0.0 if the boxes do not overlap at all, up to 1.0 for full containment of the smaller
     *         box within the larger.
     */
    public float overlapRatio(BoundingBox other) {
        float overlapX = Math.min(endX(), other.endX()) - Math.max(x, other.x);
        float overlapY = Math.min(endY(), other.endY()) - Math.max(y, other.y);
        if (overlapX <= 0 || overlapY <= 0) {
            return 0f;
        }
        float intersectionArea = overlapX * overlapY;
        float smallerArea = Math.min(width * height, other.width * other.height);
        if (smallerArea <= 0f) {
            return 0f;
        }
        return intersectionArea / smallerArea;
    }
}
