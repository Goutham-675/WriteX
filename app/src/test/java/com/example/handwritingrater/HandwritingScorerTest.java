package com.example.handwritingrater;

import java.util.Arrays;
import java.util.Random;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Synthetic-image validation of {@link HandwritingScorer} (spec Section 8).
 * Plain JVM unit tests — images are built as raw grayscale arrays so no
 * Android framework is needed. A "neat" page (consistent slant, baseline,
 * spacing, stroke width, size) must score meaningfully higher than a "messy"
 * page (per-glyph jitter), and a machine-perfect grid must be flagged printed.
 */
public class HandwritingScorerTest {

    private static final int W = 400;
    private static final int H = 220;
    private static final int LINES = 5;
    private static final int PER_LINE = 8;

    @Test
    public void neatScoresHigherThanMessyAcrossMetrics() {
        HandwritingScorer.Result neat = neat();
        HandwritingScorer.Result messy = messy();

        assertTrue("neat score " + neat.score + " should beat messy " + messy.score,
                neat.score >= messy.score + 5);

        assertTrue(neat.slantStd < messy.slantStd);
        assertTrue(neat.baseNorm < messy.baseNorm);
        assertTrue(neat.spacingCv < messy.spacingCv);
        assertTrue(neat.strokeCv < messy.strokeCv);
        assertTrue(neat.sizeCv < messy.sizeCv);
    }

    @Test
    public void messyPageIsNotFlaggedPrinted() {
        assertFalse("randomized handwriting must look handwritten", messy().printed);
        assertFalse(neat().printed);
    }

    @Test
    public void perfectRegularGridIsFlaggedPrinted() {
        assertTrue("uniform grid must look machine-printed", perfectGrid().printed);
    }

    @Test
    public void scoringIsDeterministic() {
        int[] g = neatGray();
        HandwritingScorer.Result a = HandwritingScorer.scoreGray(g, W, H);
        HandwritingScorer.Result b = HandwritingScorer.scoreGray(g, W, H);
        assertEquals(a.score, b.score);
        assertEquals(a.tier, b.tier);
    }

    @Test
    public void tierNamesCoverTheRange() {
        assertEquals("Doctor's Prescription", HandwritingScorer.tierOf(0));
        assertEquals("Font Incarnate", HandwritingScorer.tierOf(100));
        assertTrue(HandwritingScorer.tierOf(50).length() > 0);
    }

    private static HandwritingScorer.Result neat() {
        return HandwritingScorer.scoreGray(neatGray(), W, H);
    }

    private static HandwritingScorer.Result messy() {
        return HandwritingScorer.scoreGray(messyGray(), W, H);
    }

    private static HandwritingScorer.Result perfectGrid() {
        int[] g = paper();
        int x = 30;
        for (int line = 0; line < LINES; line++) {
            int base = 30 + line * 36;
            x = 30;
            for (int k = 0; k < PER_LINE; k++) {
                stem(g, x, base - 23, base, 3, 0);
                x += 3 + 7;
            }
        }
        return HandwritingScorer.scoreGray(g, W, H);
    }

    private static int[] neatGray() {
        int[] g = paper();
        Random r = new Random(42);
        for (int line = 0; line < LINES; line++) {
            int base = 30 + line * 36;
            int x = 30;
            for (int k = 0; k < PER_LINE; k++) {
                int bottom = base + (r.nextInt(9) - 4);   // small baseline wiggle
                int top = Math.max(1, bottom - 23);
                stem(g, x, top, bottom, 3, 0);
                x += 3 + 7;
            }
        }
        return g;
    }

    private static int[] messyGray() {
        int[] g = paper();
        Random r = new Random(2026);
        for (int line = 0; line < LINES; line++) {
            int base = 30 + line * 36;
            int x = 30;
            for (int k = 0; k < PER_LINE; k++) {
                int bottom = base + (r.nextInt(15) - 7);          // wobbly baseline
                int gw = 2 + r.nextInt(5);                        // varied stroke width
                int height = 20 + r.nextInt(9);                   // varied letter size
                int top = Math.max(1, bottom - height);
                int shear = r.nextInt(15) - 7;                    // varied slant
                int gap = 3 + r.nextInt(16);                      // irregular spacing
                stem(g, x, top, bottom, gw, shear);
                x += gw + gap;
            }
        }
        return g;
    }

    private static int[] paper() {
        int[] g = new int[W * H];
        Arrays.fill(g, 255);
        return g;
    }

    /** Draws a filled vertical stem, sheared so its bottom is at x and top shifted by {shear}px. */
    private static void stem(int[] g, int x, int top, int bottom, int gw, int shear) {
        int height = Math.max(1, bottom - top + 1);
        for (int y = top; y <= bottom; y++) {
            int off = (int) Math.round(shear * (double) (bottom - y) / height);
            int x0 = x + off;
            for (int i = 0; i < gw; i++) {
                int xp = x0 + i;
                if (xp >= 0 && xp < W && y >= 0 && y < H) g[y * W + xp] = 20;
            }
        }
    }
}