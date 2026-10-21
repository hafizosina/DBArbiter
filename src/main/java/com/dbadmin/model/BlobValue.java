package com.dbadmin.model;

/**
 * A binary value. Only the first {@link #MAX_KEPT} bytes are kept in memory;
 * {@code length} is the full size.
 */
public record BlobValue(byte[] head, long length) {

    public static final int MAX_KEPT = 1024 * 1024;

    public static BlobValue of(byte[] bytes) {
        return new BlobValue(bytes.length > MAX_KEPT ? java.util.Arrays.copyOf(bytes, MAX_KEPT) : bytes, bytes.length);
    }

    public boolean isTruncated() {
        return head.length < length;
    }

    @Override
    public String toString() {
        return "[BLOB " + length + " bytes]";
    }
}
