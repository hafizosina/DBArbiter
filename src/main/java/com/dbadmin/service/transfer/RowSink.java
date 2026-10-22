package com.dbadmin.service.transfer;

import java.io.IOException;
import java.util.List;

/**
 * Receives rows one at a time, e.g. to write them to a file while they are read from the database.
 * Values are as JDBC returns them (null, numbers, strings, dates, byte[] for binary data).
 */
public interface RowSink {

    void begin(List<String> columns, List<String> types) throws IOException;

    void row(List<Object> values) throws IOException;

    void end() throws IOException;
}
