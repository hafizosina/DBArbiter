package com.dbadmin.license;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Optional;

/**
 * The app's installed license: the key in ~/.dbadmin/license.txt, checked against the public key
 * built into the app. Also remembers the latest date it has seen, so turning the PC clock back
 * does not extend a license.
 */
public final class Licensing {

    /** Shown when Compare is locked. */
    public static final String CONTACT = "Hafiz Hisbullah (zhenzhu.gamedev@gmail.com)";

    private final Path file;
    private final Path seenFile;
    private final PublicKey publicKey;

    public Licensing() {
        this(Path.of(System.getProperty("user.home"), ".dbadmin"), embeddedPublicKey());
    }

    Licensing(Path dir, PublicKey publicKey) {
        this.file = dir.resolve("license.txt");
        this.seenFile = dir.resolve("license-seen");
        this.publicKey = publicKey;
    }

    /** The installed license, if there is a valid one. */
    public Optional<License> current() {
        try {
            if (!Files.exists(file)) return Optional.empty();
            return Optional.of(LicenseKey.verify(Files.readString(file, StandardCharsets.UTF_8), publicKey));
        } catch (IOException | LicenseKey.InvalidKeyException e) {
            return Optional.empty();
        }
    }

    /** Checks a pasted key without installing it. */
    public License check(String key) throws LicenseKey.InvalidKeyException {
        return LicenseKey.verify(key, publicKey);
    }

    /** Checks and installs a key, replacing the current one. */
    public License install(String key) throws LicenseKey.InvalidKeyException, IOException {
        License license = check(key);
        Files.createDirectories(file.getParent());
        Files.writeString(file, key.replaceAll("\\s+", "") + "\n", StandardCharsets.UTF_8);
        return license;
    }

    /** Whether licensed features (Compare) work now. */
    public boolean allowsCompare() {
        LocalDate today = today();
        return current().map(l -> l.allows(today)).orElse(false);
    }

    /**
     * Today, or the latest date seen before if the clock is now earlier (it was turned back).
     * Records the date it returns.
     */
    public LocalDate today() {
        LocalDate now = LocalDate.now();
        LocalDate seen = null;
        try {
            if (Files.exists(seenFile)) seen = LocalDate.ofEpochDay(Long.parseLong(Files.readString(seenFile).strip()));
        } catch (IOException | RuntimeException ignored) {
            // unreadable: start again from today
        }
        LocalDate today = seen != null && seen.isAfter(now) ? seen : now;
        if (!today.equals(seen)) {
            try {
                Files.createDirectories(seenFile.getParent());
                Files.writeString(seenFile, Long.toString(today.toEpochDay()));
            } catch (IOException ignored) {
                // checked again next time
            }
        }
        return today;
    }

    /** The public key in com/dbadmin/license/public.key (Base64 of the X.509 encoding). */
    static PublicKey embeddedPublicKey() {
        try (InputStream in = Licensing.class.getResourceAsStream("public.key")) {
            if (in == null) throw new IllegalStateException("public.key is missing from the app");
            return publicKey(new String(in.readAllBytes(), StandardCharsets.US_ASCII).strip());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Invalid public key", e);
        }
    }
}
