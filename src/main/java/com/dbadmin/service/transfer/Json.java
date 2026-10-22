package com.dbadmin.service.transfer;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;

import java.io.IOException;
import java.io.Writer;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Base64;
import java.util.List;

/** Rows as a JSON array of objects (column name -> value); binary data as Base64 text. */
public final class Json {

    private Json() {
    }

    public static RowSink writer(Writer out) {
        return new RowSink() {
            private JsonGenerator json;
            private List<String> columns;

            @Override
            public void begin(List<String> columns, List<String> types) throws IOException {
                this.columns = columns;
                json = new JsonFactory().createGenerator(out).useDefaultPrettyPrinter();
                json.writeStartArray();
            }

            @Override
            public void row(List<Object> values) throws IOException {
                json.writeStartObject();
                for (int i = 0; i < columns.size(); i++) {
                    json.writeFieldName(columns.get(i));
                    Object v = values.get(i);
                    if (v == null) json.writeNull();
                    else if (v instanceof Boolean b) json.writeBoolean(b);
                    else if (v instanceof BigDecimal d) json.writeNumber(d);
                    else if (v instanceof BigInteger n) json.writeNumber(n);
                    else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) json.writeNumber(((Number) v).longValue());
                    else if (v instanceof Double || v instanceof Float) {
                        double d = ((Number) v).doubleValue();
                        if (Double.isFinite(d)) json.writeNumber(d);
                        else json.writeString(v.toString());
                    } else if (v instanceof byte[] bytes) json.writeString(Base64.getEncoder().encodeToString(bytes));
                    else json.writeString(v.toString());
                }
                json.writeEndObject();
            }

            @Override
            public void end() throws IOException {
                json.writeEndArray();
                json.flush();
            }
        };
    }
}
