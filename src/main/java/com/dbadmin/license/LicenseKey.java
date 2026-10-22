package com.dbadmin.license;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Base64;

/**
 * Turns a {@link License} into a one-line key and back: "DBA1-" plus the license and its Ed25519
 * signature in URL-safe Base64. The key is not secret, only tamper-proof: changing any part of it
 * breaks the signature, and only the holder of the private key can sign.
 *
 * <pre>
 * version (1) · type (1) · issued epoch day (4) · expires epoch day, -1 = never (4)
 * · name length (1) · name UTF-8 · signature (64)
 * </pre>
 */
public final class LicenseKey {

    public static final String PREFIX = "DBA1-";
    private static final byte VERSION = 1;
    private static final int SIGNATURE_LENGTH = 64;
    private static final int MAX_NAME_BYTES = 120;

    private LicenseKey() {
    }

    /** Signs {@code license} with the private key and returns the key text. */
    public static String sign(License license, PrivateKey key) throws GeneralSecurityException {
        byte[] payload = payload(license);
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(key);
        s.update(payload);
        byte[] signature = s.sign();
        byte[] all = Arrays.copyOf(payload, payload.length + signature.length);
        System.arraycopy(signature, 0, all, payload.length, signature.length);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(all);
    }

    /**
     * Reads and checks a key. Whitespace and line breaks (from pasting) are ignored.
     *
     * @throws InvalidKeyException with a message for the user if it is not a valid DBAdmin key
     */
    public static License verify(String text, PublicKey key) throws InvalidKeyException {
        String compact = text == null ? "" : text.replaceAll("\\s+", "");
        if (!compact.startsWith(PREFIX)) throw new InvalidKeyException("This is not a DBAdmin license key.");
        byte[] all;
        try {
            all = Base64.getUrlDecoder().decode(compact.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new InvalidKeyException("The key is damaged (it was not copied completely?).");
        }
        if (all.length < 11 + SIGNATURE_LENGTH) throw new InvalidKeyException("The key is incomplete.");
        byte[] payload = Arrays.copyOf(all, all.length - SIGNATURE_LENGTH);
        byte[] signature = Arrays.copyOfRange(all, payload.length, all.length);
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(payload);
            if (!s.verify(signature)) throw new InvalidKeyException("The key is not valid (it was changed or not issued for DBAdmin).");
        } catch (GeneralSecurityException e) {
            throw new InvalidKeyException("The key is not valid (it was changed or not issued for DBAdmin).");
        }
        return parse(payload);
    }

    private static byte[] payload(License license) {
        byte[] name = license.name().strip().getBytes(StandardCharsets.UTF_8);
        if (name.length == 0) throw new IllegalArgumentException("The name is empty.");
        if (name.length > MAX_NAME_BYTES) throw new IllegalArgumentException("The name is too long (max " + MAX_NAME_BYTES + " bytes).");
        return ByteBuffer.allocate(11 + name.length)
                .put(VERSION)
                .put((byte) license.type().ordinal())
                .putInt((int) license.issued().toEpochDay())
                .putInt(license.expires() == null ? -1 : (int) license.expires().toEpochDay())
                .put((byte) name.length)
                .put(name)
                .array();
    }

    private static License parse(byte[] payload) throws InvalidKeyException {
        ByteBuffer in = ByteBuffer.wrap(payload);
        if (in.get() != VERSION) throw new InvalidKeyException("This key is for a newer version of DBAdmin.");
        int type = in.get();
        if (type < 0 || type >= License.Type.values().length) throw new InvalidKeyException("Unknown license type.");
        LocalDate issued = LocalDate.ofEpochDay(in.getInt());
        int expires = in.getInt();
        int nameLength = in.get() & 0xff;
        if (in.remaining() != nameLength) throw new InvalidKeyException("The key is damaged.");
        byte[] name = new byte[nameLength];
        in.get(name);
        return new License(License.Type.values()[type], new String(name, StandardCharsets.UTF_8), issued,
                expires == -1 ? null : LocalDate.ofEpochDay(expires));
    }

    /** A key that cannot be used; the message is meant for the user. */
    public static final class InvalidKeyException extends Exception {
        public InvalidKeyException(String message) {
            super(message);
        }
    }
}
