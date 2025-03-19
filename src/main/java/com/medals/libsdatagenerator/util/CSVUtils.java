package com.medals.libsdatagenerator.util;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * @author Siddharth Prince | 03/02/2025 5:23 PM
 */

public class CSVUtils {

    /**
     * Reads the CSV at the given path and returns a list of CSVRecords.
     * The CSV is assumed to have a header.
     */
    public static List<CSVRecord> readCsvWithHeader(Path csvPath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            CSVParser parser = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);
            return parser.getRecords();
        }
    }

    /**
     * Returns the header map from the CSV (column name to column index).
     */
    public static Map<String, Integer> getHeaderMap(Path csvPath) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(csvPath)) {
            CSVParser parser = CSVFormat.DEFAULT
                    .withFirstRecordAsHeader()
                    .parse(reader);
            return parser.getHeaderMap();
        }
    }

    /**
     * Returns a CSVPrinter that writes to the given csvPath.
     * If append is true, it opens the file in append mode.
     * If append is false and the file exists, the file is backed up (renamed with a timestamp)
     * and a new file with headers is created.
     *
     * @param csvPath The path to the CSV file.
     * @param append  True to append rows; false to create a new file (after backing up the old one, if any).
     * @param header  The header columns to write if creating a new file.
     */
    public static CSVPrinter getCsvPrinter(Path csvPath, boolean append, String[] header) throws IOException {
        // If not appending and file exists, back it up.
        if (!append && Files.exists(csvPath)) {
            backupCsv(csvPath);
        }
        BufferedWriter writer;
        if (append) {
            writer = Files.newBufferedWriter(csvPath,
                    StandardOpenOption.APPEND,
                    StandardOpenOption.CREATE);
            // Use DEFAULT format without writing headers again.
            return new CSVPrinter(writer, CSVFormat.DEFAULT);
        } else {
            // Create new file (truncate if exists) and write header.
            writer = Files.newBufferedWriter(csvPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING);
            return new CSVPrinter(writer, CSVFormat.DEFAULT.withHeader(header));
        }
    }

    /**
     * Backs up the existing CSV file by renaming it with a timestamp.
     */
    private static void backupCsv(Path csvPath) throws IOException {
        String timestamp = new SimpleDateFormat("yyyyMMddHHmmss").format(new Date());
        String originalName = csvPath.getFileName().toString();
        String backupName = originalName.replaceAll("\\.csv$", "_" + timestamp + ".csv");
        Path backupPath = csvPath.resolveSibling(backupName);
        Files.move(csvPath, backupPath, StandardCopyOption.REPLACE_EXISTING);
    }
}