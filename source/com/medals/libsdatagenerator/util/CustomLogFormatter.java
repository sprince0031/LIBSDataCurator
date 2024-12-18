package com.medals.libsdatagenerator.util;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * @author Siddharth Prince | 16/12/24 18:15
 */

public class CustomLogFormatter extends SimpleFormatter {

    @Override
    public synchronized String format(LogRecord record) {
        String message = formatMessage(record);
        String name = record.getLoggerName();
        long timestamp = record.getMillis();
        String level = record.getLevel().toString();
        SimpleDateFormat dateFormat = new SimpleDateFormat("[dd-MM-yyyy HH:mm:ss:SSS]");
        Throwable thrown = record.getThrown();

        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append(dateFormat.format(timestamp));
        stringBuilder.append("|[").append(name);
        stringBuilder.append("]|[").append(level).append("]: ");
        stringBuilder.append(message);

        if (thrown != null) {
            stringBuilder.append(" ").append(System.lineSeparator());
            StringWriter stringWriter = new StringWriter(1024);
            PrintWriter printWriter = new PrintWriter(stringWriter);
            thrown.printStackTrace(printWriter);
            printWriter.close();
            stringBuilder.append(stringWriter);
        }
        stringBuilder.append(" ").append(System.lineSeparator());

        return stringBuilder.toString();
    }
}
