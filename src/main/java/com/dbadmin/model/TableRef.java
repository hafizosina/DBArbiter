package com.dbadmin.model;

public record TableRef(String catalog, String schema, String name, String type) {

    public boolean isView() {
        return type != null && type.toUpperCase().contains("VIEW");
    }

    /** A materialized view (PostgreSQL); also counts as a view. */
    public boolean isMaterializedView() {
        return type != null && type.toUpperCase().contains("MATERIALIZED");
    }

    public String displayName() {
        return schema != null ? schema + "." + name : catalog != null ? catalog + "." + name : name;
    }
}
