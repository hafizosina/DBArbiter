package com.dbadmin.model;

public record TableRef(String catalog, String schema, String name, String type) {

    public boolean isView() {
        return type != null && type.toUpperCase().contains("VIEW");
    }

    public String displayName() {
        return schema != null ? schema + "." + name : catalog != null ? catalog + "." + name : name;
    }
}
