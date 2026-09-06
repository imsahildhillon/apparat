package com.apparat.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal CSV reader/writer — deliberately hand-rolled rather than pulling in
 * a library, since Apparat's CSV needs (quoted fields with embedded commas
 * for the "location" column, header-based column access) are small and the
 * point of this file-handling requirement is to demonstrate real I/O, not to
 * demonstrate a third-party dependency.
 */
public final class CsvUtil {

    private CsvUtil() { }

    /** Parses a CSV input into a list of header->value maps, preserving header order. Handles double-quoted fields containing commas. */
    public static List<Map<String, String>> readCsv(InputStream in) {
        List<Map<String, String>> rows = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String headerLine = reader.readLine();
            if (headerLine == null) return rows;
            String[] headers = splitCsvLine(headerLine);
            String line;
            int lineNumber = 1;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                String[] values = splitCsvLine(line);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    row.put(headers[i].trim(), i < values.length ? values[i].trim() : "");
                }
                row.put("__line", String.valueOf(lineNumber));
                rows.add(row);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return rows;
    }

    private static String[] splitCsvLine(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        fields.add(current.toString());
        return fields.toArray(new String[0]);
    }

    /** Writes rows of equal length under the given headers, quoting any field containing a comma. */
    public static void writeCsv(OutputStream out, List<String> headers, List<List<String>> rows) {
        try (PrintWriter writer = new PrintWriter(out, false, StandardCharsets.UTF_8)) {
            writer.println(String.join(",", headers));
            for (List<String> row : rows) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < row.size(); i++) {
                    if (i > 0) line.append(',');
                    line.append(quoteIfNeeded(row.get(i)));
                }
                writer.println(line);
            }
        }
    }

    private static String quoteIfNeeded(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
