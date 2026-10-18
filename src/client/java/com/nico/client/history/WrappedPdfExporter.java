package com.nico.client.history;

import com.nico.client.utils.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Writes a PDF representation of the cards shown by SkyBlock Wrapped. */
public final class WrappedPdfExporter {
    private static final Charset PDF_CHARSET = Charset.forName("windows-1252");
    private static final DateTimeFormatter FILE_MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT);
    private static final DateTimeFormatter DISPLAY_MONTH = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ROOT);
    private static final DateTimeFormatter DISPLAY_DATE = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH);
    private static final Path EXPORT_DIR = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("nicos_super_mods")
            .resolve("wrapped_exports");

    private static final int PAGE_WIDTH = 842;
    private static final int PAGE_HEIGHT = 595;

    private WrappedPdfExporter() { }

    public static Path export(
            YearMonth month,
            WrappedStore.WrappedSnapshot snapshot,
            List<WrappedScreen.Slide> slides
    ) throws IOException {
        if (month == null) throw new IllegalArgumentException("month");
        if (snapshot == null) throw new IllegalArgumentException("snapshot");
        if (slides == null || slides.isEmpty()) throw new IllegalArgumentException("Wrapped has no slides to export");

        Path directory = ensureExportDirectory();
        Path output = directory.resolve("skyblock-wrapped-" + month.format(FILE_MONTH) + ".pdf");
        byte[] pdf = createPdf(month, snapshot, slides);
        AtomicFiles.writeAtomically(output, temporary -> Files.write(temporary, pdf));
        return output;
    }

    public static Path exportDirectory() {
        return EXPORT_DIR.toAbsolutePath().normalize();
    }

    public static Path ensureExportDirectory() throws IOException {
        Files.createDirectories(EXPORT_DIR);
        return exportDirectory();
    }

    private static byte[] createPdf(
            YearMonth month,
            WrappedStore.WrappedSnapshot snapshot,
            List<WrappedScreen.Slide> slides
    ) throws IOException {
        int pageCount = slides.size();
        int firstPageObject = 5;
        int objectCount = 4 + pageCount * 2;
        List<byte[]> objects = new ArrayList<>(objectCount + 1);
        objects.add(null); // PDF object numbers are 1-indexed.

        objects.add(bytes("<< /Type /Catalog /Pages 2 0 R >>"));

        StringBuilder kids = new StringBuilder();
        for (int i = 0; i < pageCount; i++) {
            if (i > 0) kids.append(' ');
            kids.append(firstPageObject + i * 2).append(" 0 R");
        }
        objects.add(bytes("<< /Type /Pages /Kids [" + kids + "] /Count " + pageCount + " >>"));
        objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAsciEncoding >>"));
        objects.add(bytes("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAsciEncoding >>"));

        for (int i = 0; i < pageCount; i++) {
            int pageObject = firstPageObject + i * 2;
            int contentObject = pageObject + 1;
            String page = "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 " + PAGE_WIDTH + " " + PAGE_HEIGHT + "] "
                    + "/Resources << /Font << /F1 3 0 R /F2 4 0 R >> >>"
                    + "/Contents " + contentObject + " 0 R >>";
            objects.add(bytes(page));

            byte[] stream = pageStream(month, snapshot, slides.get(i), i + 1, pageCount);
            ByteArrayOutputStream content = new ByteArrayOutputStream();
            write(content, "<< /Length " + stream.length + " >>\nstream\n");
            content.write(stream);
            write(content, "\nendstream");
            objects.add(content.toByteArray());
        }

        ByteArrayOutputStream pdf = new ByteArrayOutputStream();
        write(pdf, "%PDF-1.4\n%\u00E2\u00E3\u00CF\u00D3\n");
        long[] offsets = new long[objectCount + 1];

        for (int objectNumber = 1; objectNumber <= objectCount; objectNumber++) {
            offsets[objectNumber] = pdf.size();
            write(pdf, objectNumber + " 0 obj\n");
            pdf.write(objects.get(objectNumber));
            write(pdf, "\nendobj\n");
        }

        long xrefOffset = pdf.size();
        write(pdf, "xref\n0 " + (objectCount + 1) + "\n");
        write(pdf, "0000000000 65535 f \n");
        for (int objectNumber = 1; objectNumber <= objectCount; objectNumber++) {
            write(pdf, String.format(Locale.ROOT, "%010d 00000 n \n", offsets[objectNumber]));
        }

        write(pdf, "trailer\n<< /Size " + (objectCount + 1) + " /Root 1 0 R >>\n");
        write(pdf, "startxref\n" + xrefOffset + "\n%%EOF\n");
        return pdf.toByteArray();
    }

    private static byte[] pageStream(
            YearMonth month,
            WrappedStore.WrappedSnapshot snapshot,
            WrappedScreen.Slide slide,
            int pageNumber,
            int pageCount
    ) {
        StringBuilder content = new StringBuilder();
        int[] palette = WrappedScreen.PALETTES[Math.floorMod(slide.palette(), WrappedScreen.PALETTES.length)];

        drawBackground(content, palette, slide.palette() * 31 + pageNumber * 17);
        drawProgress(content, pageNumber, pageCount);
        drawHeader(content, slide);

        if (slide.kind() == WrappedScreen.SlideKind.INTRO) {
            drawIntro(content, month, snapshot);
        } else if (slide.kind() == WrappedScreen.SlideKind.TIME) {
            drawTimeStats(content, slide.stats());
            drawHeatmap(content, snapshot);
        } else {
            drawStats(content, slide.stats(), 8);
        }

        drawCenteredText(content, "F1", 9, PAGE_WIDTH / 2, 24, 0.90, 0.90, 0.94,
                "Nico's Super Mods  -  " + pageNumber + " / " + pageCount);
        return bytes (content.toString());
    }

    private static void drawBackground(StringBuilder content, int[] palette, int seed) {
        int top = mix(palette[0], palette[1], 0.20);
        int bottom = mix(palette[0], palette[1], 0.68);
        int strips = 42;

        for (int i = 0; i < strips; i++) {
            double t = (double) i / Math.max(1, strips - 1);
            int color = mix(bottom, top, t);
            double y = (double) i * PAGE_HEIGHT / strips;
            double height = (double) PAGE_HEIGHT / strips + 1.0;
            drawRect(content, 0, y, PAGE_WIDTH, height, color);
        }

        // Scatter several visible accents across each page. The layout is deterministic for
        // a given slide, but changes from page to page so exported Wrapped cards do not all
        // share the same decoration.
        for (int i = 0; i < 14; i++) {
            boolean bright = (i & 1) == 0;
            int base = bright ? palette[1] : palette[0];
            int accent = mix(base, bright ? 0xFFFFFFFF : 0xFF101018,
                    0.08 + pseudoRandom(seed, i, 0) * 0.12);
            double width = 30 + pseudoRandom(seed, i, 1) * 105;
            double height = 5 + pseudoRandom(seed, i, 2) * 15;
            double x = 14 + pseudoRandom(seed, i, 3) * (PAGE_WIDTH - width - 28);
            double y = 58 + pseudoRandom(seed, i, 4) * (PAGE_HEIGHT - height - 116);
            drawRect(content, x, y, width, height, accent);
        }

        drawRect(content, 0, PAGE_HEIGHT - 32, PAGE_WIDTH, 32, mix(top, 0xFF000000, 0.52));
        drawRect(content, 0, 0, PAGE_WIDTH, 54, mix(bottom, 0xFF000000, 0.58));
    }

    private static double pseudoRandom(int seed, int index, int salt) {
        long value = Integer.toUnsignedLong(seed);
        value ^= (long) (index + 1) * 0x9E3779B97F4A7C15L;
        value ^= (long) (salt + 1) * 0xC2B2AE3D27D4EB4FL;
        value ^= value >>> 30;
        value *= 0xBF58476D1CE4E5B9L;
        value ^= value >>> 27;
        value *= 0x94D049BB133111EBL;
        value ^= value >>> 31;
        return (value >>> 11) * 0x1.0p-53;
    }

    private static void drawProgress(StringBuilder content, int pageNumber, int pageCount) {
        double left = 18;
        double right = PAGE_WIDTH - 18;
        double gap = 4;
        double segmentWidth = (right - left - gap * (pageCount - 1)) / Math.max(1, pageCount);

        for (int i = 0; i < pageCount; i++) {
            double x = left + i * (segmentWidth + gap);
            drawRect(content, x, PAGE_HEIGHT - 13, segmentWidth, 3, 0x66FFFFFF);
            if (i < pageNumber) {
                drawRect(content, x, PAGE_HEIGHT - 13, segmentWidth, 3, 0xFFFFFFFF);
            }
        }
    }

    private static void drawHeader(StringBuilder content, WrappedScreen.Slide slide) {
        if (slide.kicker() != null && !slide.kicker().isBlank()) {
            drawCenteredText(content, "F1", 13, PAGE_WIDTH / 2, 493, 0.92, 0.92, 0.96,
                    slide.kicker().toUpperCase(Locale.ROOT));
        }

        int titleSize = titleSize(slide.title());
        drawCenteredText(content, "F2", titleSize, PAGE_WIDTH / 2, 445, 1.0, 1.0, 1.0, slide.title());

        if (slide.subtitle() != null  && !slide.subtitle().isBlank()) {
            drawCenteredText(content, "F1", 13, PAGE_WIDTH / 2, 414, 0.94, 0.94, 0.97, slide.subtitle());
        }
    }

    private static void drawIntro(StringBuilder content, YearMonth month, WrappedStore.WrappedSnapshot snapshot) {
        String range = snapshot.start.format(DISPLAY_DATE) + "  -  " + snapshot.end.format(DISPLAY_DATE);
        drawCenteredText(content, "F1", 13, PAGE_WIDTH / 2, 340, 0.86, 0.86, 0.89, range);
        drawCenteredText(content, "F1", 15, PAGE_WIDTH / 2, 312, 0.94, 0.94, 0.97,
                month.format(DISPLAY_MONTH) + " in SkyBlock");

        if (snapshot.playtimeSeconds > 0) {
            drawCenteredText(content, "F2", 34, PAGE_WIDTH / 2, 245, 1.0, 1.0, 1.0,
                    formatDuration(snapshot.playtimeSeconds));
            drawCenteredText(content, "F1", 12, PAGE_WIDTH / 2, 220, 0.91, 0.91, 0.94,
                    "tracked playtime");
        }
    }

    private static void drawTimeStats(StringBuilder content, List<WrappedScreen.Stat> stats) {
        int shown = Math.min(4, stats.size());
        if (shown <= 0) return;

        double gap = 12;
        double sideMargin = 50;
        double cardHeight = 52;
        double cardWidth = (PAGE_WIDTH - sideMargin * 2 - gap * (shown - 1)) / shown;
        double y = 310;

        for (int i = 0; i < shown; i++) {
            WrappedScreen.Stat stat = stats.get(i);
            double x = sideMargin + i * (cardWidth + gap);

            drawRect(content, x, y, cardWidth, cardHeight, 0xB30A0A10);
            drawRect(content, x, y, 4, cardHeight, 0xFFFFFFFF);

            String value = fitText(stat.value(), 14, cardWidth - 22);
            String label = fitText(stat.label(), 9, cardWidth - 22);
            drawText(content, "F2", 14, x + 12, y + 30, 1.0, 1.0, 1.0, value);
            drawText(content, "F1", 9, x + 12, y + 13, 0.84, 0.84, 0.87, label);
        }
    }

    private static void drawStats(StringBuilder content, List<WrappedScreen.Stat> stats, int maxStats) {
        int shown = Math.min(maxStats, stats.size());
        if (shown <= 0) {
            drawCenteredText(content, "F1", 13, PAGE_WIDTH / 2, 320, 0.93, 0.93, 0.96,
                    "Nothing meaningful was recorded for this card.");
            return;
        }

        int columns = 2;
        double gap = 16;
        double cardWidth = 300;
        double cardHeight = 54;
        double gridWidth = columns * cardWidth + gap;
        double left = (PAGE_WIDTH - gridWidth) / 2.0;
        double firstTop = 365;
        double rowGap = 12;

        for (int i = 0; i < shown; i++) {
            WrappedScreen.Stat stat = stats.get(i);
            int column = i % columns;
            int row = i / columns;
            double x = left + column * (cardWidth + gap);
            double top = firstTop - row * (cardHeight + rowGap);
            double y = top - cardHeight;

            drawRect(content, x, y, cardWidth, cardHeight, 0xB30A0A10);
            drawRect(content, x, y, 4, cardHeight, 0xFFFFFFFF);

            String value = fitText(stat.value(), 15, cardWidth - 24);
            String label = fitText(stat.label(), 10, cardWidth - 24);
            drawText(content, "F2", 15, x + 13, y + 31, 1.0, 1.0, 1.0, value);
            drawText(content, "F1", 10, x + 13, y + 14, 0.84, 0.84, 0.87, label);
        }
    }

    private static void drawHeatmap(StringBuilder content, WrappedStore.WrappedSnapshot snapshot) {
        if (snapshot.playtimeByDay.isEmpty()) return;

        LocalDate start = snapshot.start;
        LocalDate end = snapshot.end;
        LocalDate gridStart = start.minusDays(start.getDayOfWeek().getValue() - 1L);
        LocalDate gridEnd = end.plusDays(7L - end.getDayOfWeek().getValue());
        int weeks = (int) Math.max(1L, ChronoUnit.WEEKS.between(gridStart, gridEnd.plusDays(1)));

        long max = 1L;
        int activeDays = 0;
        long totalDays = ChronoUnit.DAYS.between(start, end) + 1L;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            long seconds = snapshot.playtimeByDay.getOrDefault(day, 0L);
            if (seconds > 0) activeDays++;
            max = Math.max(max, seconds);
        }

        drawCenteredText(content, "F2", 12, PAGE_WIDTH / 2, 280, 1.0, 1.0, 1.0, "PLAYTIME BY DAY");
        drawCenteredText(content, "F1", 9, PAGE_WIDTH / 2, 263, 0.88, 0.88, 0.91,
                activeDays + " of " + totalDays + " days played  -  brighter = more playtime");

        double cell = 24;
        double gap = 2;
        double gridWidth = weeks * cell + (weeks - 1) * gap;
        double gridHeight = 7 * cell + 6 * gap;
        double left = (PAGE_WIDTH - gridWidth) / 2.0;
        double bottom = 64;

        drawRect(content, left - 42, bottom - 10, gridWidth + 84, gridHeight + 20, 0xFF111119);
        drawText(content, "F1", 9, left - 34, bottom + 6 * (cell + gap) + 7, 0.86, 0.86, 0.89, "Mon");
        drawText(content, "F1", 9, left - 34, bottom + 4 * (cell + gap) + 7, 0.86, 0.86, 0.89, "Wed");
        drawText(content, "F1", 9, left - 34, bottom + 2 * (cell + gap) + 7, 0.86, 0.86, 0.89, "Fri");

        for (LocalDate cursor = start; !cursor.isAfter(end); cursor = cursor.plusDays(1)) {
            int week = (int) (ChronoUnit.DAYS.between(gridStart, cursor) / 7L);
            int day = cursor.getDayOfWeek().getValue() - 1;
            double x = left + week * (cell + gap);
            double y = bottom + (6 - day) * (cell + gap);
            long seconds = snapshot.playtimeByDay.getOrDefault(cursor, 0L);
            double intensity = seconds <= 0 ? 0.0 : Math.max(0.18, Math.sqrt((double) seconds / max));
            int color = seconds <= 0 ? 0xFF26242C : mix(0xFF2D6134, 0xFFE6FF8A, intensity);
            drawRect(content, x, y, cell, cell, color);
        }
    }

    private static void drawRect(StringBuilder content, double x, double y, double width, double height, int color) {
        appendColor(content, color);
        content.append(format(x)).append(' ')
                .append(format(y)).append(' ')
                .append(format(width)).append(' ')
                .append(format(height)).append(" re f\n");
    }

    private static void drawCenteredText(
            StringBuilder content,
            String font,
            int size,
            double centerX,
            double y,
            double red,
            double green,
            double blue,
            String text
    ) {
        String safe = fitText(text, size, PAGE_WIDTH - 100);
        double x = centerX - estimateTextWidth(safe, size) / 2.0;
        drawText(content, font, size, x, y, red, green, blue, safe);
    }

    private static void drawText(
            StringBuilder content,
            String font,
            int size,
            double x,
            double y,
            double red,
            double green,
            double blue,
            String text
    ) {
        content.append(String.format(Locale.ROOT, "%.3f %.3f %.3f rg\n", red, green, blue));
        content.append("BT /").append(font).append(' ').append(size)
                .append(" Tf 1 0 0 1 ").append(format(x)).append(' ').append(format(y)).append(" Tm (")
                .append(escape(text))
                .append(") Tj ET\n");
    }

    private static void appendColor(StringBuilder content, int color) {
        double red = ((color >> 16) & 0xFF) / 255.0;
        double green = ((color >> 8) & 0xFF) / 255.0;
        double blue = (color & 0xFF) / 255.0;
        content.append(String.format(Locale.ROOT, "%.3f %.3f %.3f rg\n", red, green, blue));
    }

    private static int titleSize(String title) {
        int length = title == null ? 0 : title.length();
        if (length < 13) return 35;
        if (length < 20) return 29;
        return 23;
    }

    private static String fitText(String text, int size, double maxWidth) {
        if (text == null) return "";
        if (estimateTextWidth(text, size) <= maxWidth) return text;

        String suffix = "...";
        String value = text;
        while (!value.isEmpty() && estimateTextWidth(value + suffix, size) > maxWidth) {
            value = value.substring(0, value.length() - 1);
        }
        return value + suffix;
    }

    private static double estimateTextWidth(String text, int size) {
        if (text == null || text.isEmpty()) return 0.0;
        double units = 0.0;
        for (char c : text.toCharArray()) {
            if (c == ' ' || c == '.' || c == ',' || c == ':' || c == ';' || c == '!' || c == 'i' || c == 'l') {
                units += 0.28;
            } else if (c == 'W' || c == 'M' || c == 'w' || c == 'm') {
                units += 0.82;
            } else if (Character.isUpperCase(c)) {
                units += 0.64;
            } else {
                units += 0.53;
            }
        }
        return units * size;
    }

    private static int mix(int from, int to, double t) {
        double clamped = Math.clamp(t, 0.0, 1.0);
        int fr = (from >> 16) & 0xFF;
        int fg = (from >> 8) & 0xFF;
        int fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF;
        int tg = (to >> 8) & 0xFF;
        int tb = to & 0xFF;
        int r = (int) Math.round(fr + (tr - fr) * clamped);
        int g = (int) Math.round(fg + (tg - fg) * clamped);
        int b = (int) Math.round(fb + (tb - fb) * clamped);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static String formatDuration(long seconds) {
        if (seconds <= 0) return "0m";
        long days = seconds / 86_400L;
        long hours = (seconds % 86_400L) / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        if (days > 0) return days + "d " + hours + "h";
        if (hours > 0) return hours + "h " + minutes + "m";
        return Math.max(1L, minutes) + "m";
    }

    private static String escape(String text) {
        if (text == null) return "";
        String normalized = text
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u201c', '"')
                .replace('\u201d', '"')
                .replace("\u2022", "-")
                .replace("\u2013", "-")
                .replace("\u2014", "-");

        StringBuilder safe = new StringBuilder(normalized.length());
        for (char character : normalized.toCharArray()) {
            if (!PDF_CHARSET.newEncoder().canEncode(character)) {
                safe.append('?');
            } else if (character == '\\' || character == '(' || character == ')') {
                safe.append('\\').append(character);
            } else if (character == '\r' || character == '\n') {
                safe.append(' ');
            } else {
                safe.append(character);
            }
        }
        return safe.toString();
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(PDF_CHARSET);
    }

    private static void write(ByteArrayOutputStream output, String value) throws IOException {
        output.write(bytes(value));
    }
}
