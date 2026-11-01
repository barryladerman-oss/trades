package com.trades.trading;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Reads Tradier/Apex trade confirmation PDFs and records, for each option fill, whether the broker flagged it as an
 * opening or closing contract.
 */
@Component
public class StatementFills {
    private static final Logger LOGGER = LoggerFactory.getLogger(StatementFills.class);
    private static final Pattern FILL = Pattern.compile(
            "^\\d+\\s+([BS])\\s+(\\d\\d)/(\\d\\d)/(\\d\\d)\\s+\\d\\d/\\d\\d/\\d\\d\\s+([\\d,]+)\\s+[\\d.]+\\s.*");
    private static final Pattern DESC = Pattern.compile(
            "^Desc:\\s+(PUT|CALL)\\s+(\\S+)\\s+(\\d\\d)/(\\d\\d)/(\\d\\d)\\s+([\\d.]+)\\s.*?(OPEN|CLOSING) CONTRACT.*");

    private final Path directory;

    public StatementFills(@Value("${trades.statements-dir:statements}") String directory) {
        this.directory = Path.of(directory);
    }

    /** True when the broker flagged the fill as closing, false when opening; absent when no statement matches. */
    public static final class Flags {
        private final Map<String, Deque<Boolean>> byFill = new HashMap<>();

        void add(String date, String optionSymbol, boolean buy, BigDecimal quantity, boolean closing) {
            byFill.computeIfAbsent(key(date, optionSymbol, buy, quantity), k -> new ArrayDeque<>()).add(closing);
        }

        /** Consumes the next matching statement fill. */
        public Boolean take(String date, String optionSymbol, boolean buy, BigDecimal quantity) {
            Deque<Boolean> queue = byFill.get(key(date, optionSymbol, buy, quantity));
            return queue == null ? null : queue.poll();
        }

        public int size() {
            return byFill.values().stream().mapToInt(Deque::size).sum();
        }

        private static String key(String date, String optionSymbol, boolean buy, BigDecimal quantity) {
            return date + "|" + optionSymbol + "|" + (buy ? "B" : "S") + "|" + quantity.abs().stripTrailingZeros()
                    .toPlainString();
        }
    }

    public Flags load() {
        Flags flags = new Flags();
        if (!Files.isDirectory(directory)) {
            return flags;
        }
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                    .sorted(Comparator.naturalOrder()).toList()) {
                try (PDDocument document = Loader.loadPDF(file.toFile())) {
                    parse(new PDFTextStripper().getText(document), flags);
                } catch (IOException | RuntimeException exception) {
                    LOGGER.warn("Could not read statement {}: {}", file.getFileName(), exception.getMessage());
                }
            }
        } catch (IOException exception) {
            LOGGER.warn("Could not list statements in {}: {}", directory, exception.getMessage());
        }
        return flags;
    }

    static void parse(String text, Flags flags) {
        List<String> lines = text.lines().map(String::trim).toList();
        for (int i = 0; i < lines.size() - 1; i++) {
            Matcher fill = FILL.matcher(lines.get(i));
            if (!fill.matches()) {
                continue;
            }
            Matcher desc = DESC.matcher(lines.get(i + 1));
            if (!desc.matches()) {
                continue;
            }
            String date = "20" + fill.group(4) + "-" + fill.group(2) + "-" + fill.group(3);
            BigDecimal strike = new BigDecimal(desc.group(6)).movePointRight(3);
            String symbol = desc.group(2) + desc.group(5) + desc.group(3) + desc.group(4)
                    + ("PUT".equals(desc.group(1)) ? "P" : "C") + String.format("%08d", strike.intValue());
            flags.add(date, symbol, "B".equals(fill.group(1)),
                    new BigDecimal(fill.group(5).replace(",", "")), "CLOSING".equals(desc.group(7)));
        }
    }
}
