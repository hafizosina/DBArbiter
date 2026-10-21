package com.dbadmin.model;

import java.util.List;

/** A generic grid of values: column headers plus rows. */
public record TableData(List<String> columns, List<List<Object>> rows) {
}
