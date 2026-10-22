package com.dbadmin.licensetool;

import com.dbadmin.license.License;
import com.dbadmin.license.LicenseKey;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * The license signer's files in ~/.dbadmin-signing: the private key (never part of the app or the repo),
 * its public key (built into the app as com/dbadmin/license/public.key) and the list of issued keys.
 */
final class SigningKeys {

    static final Path DIR = Path.of(System.getProperty("user.home"), ".dbadmin-signing");
    private static final Path PRIVATE = DIR.resolve("private.key");
    private static final Path PUBLIC = DIR.resolve("public.key");
    private static final Path ISSUED = DIR.resolve("issued.tsv");

    private SigningKeys() {
    }

    static boolean exist() {
        return Files.exists(PRIVATE);
    }

    /** Creates the key pair (only once: a new pair would make every issued key invalid). Returns the public key. */
    static String create() throws IOException, GeneralSecurityException {
        if (exist()) throw new IOException(PRIVATE + " already exists");
        KeyPair pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Files.createDirectories(DIR);
        Files.writeString(Files.createFile(PRIVATE, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))),
                Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded()) + "\n");
        String pub = Base64.getEncoder().encodeToString(pair.getPublic().getEncoded());
        Files.writeString(PUBLIC, pub + "\n");
        return pub;
    }

    static PrivateKey privateKey() throws IOException, GeneralSecurityException {
        byte[] encoded = Base64.getDecoder().decode(Files.readString(PRIVATE).strip());
        return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(encoded));
    }

    /** One issued key, as listed in issued.tsv. */
    record Issued(LocalDate on, License license, String key) {
    }

    static void record(License license, String key) throws IOException {
        String line = String.join("\t", LocalDate.now().toString(), license.type().name(), license.name().replace('\t', ' '),
                license.issued().toString(), license.expires() == null ? "" : license.expires().toString(), key) + "\n";
        Files.writeString(ISSUED, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Issued keys, newest first. */
    static List<Issued> issued() {
        List<Issued> result = new ArrayList<>();
        try {
            if (!Files.exists(ISSUED)) return result;
            for (String line : Files.readAllLines(ISSUED, StandardCharsets.UTF_8)) {
                String[] f = line.split("\t", -1);
                if (f.length < 6) continue;
                License l = new License(License.Type.valueOf(f[1]), f[2], LocalDate.parse(f[3]), f[4].isEmpty() ? null : LocalDate.parse(f[4]));
                result.add(0, new Issued(LocalDate.parse(f[0]), l, f[5]));
            }
        } catch (IOException | RuntimeException ignored) {
            // a damaged line: show what could be read
        }
        return result;
    }

    /** Signs a license, checks the key against the app's public key, and records it. */
    static String issue(License license) throws IOException, GeneralSecurityException, LicenseKey.InvalidKeyException {
        String key = LicenseKey.sign(license, privateKey());
        // the same check the app does: catches a signing key that does not match the app's public key
        new com.dbadmin.license.Licensing().check(key);
        record(license, key);
        return key;
    }
}
