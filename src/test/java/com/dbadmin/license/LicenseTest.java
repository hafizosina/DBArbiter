package com.dbadmin.license;

import com.dbadmin.license.License.Status;
import com.dbadmin.license.License.Type;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LicenseTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 23);
    private final KeyPair keys = pair();

    private static KeyPair pair() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private String sign(License l) throws Exception {
        return LicenseKey.sign(l, keys.getPrivate());
    }

    @Test
    void keyRoundTripsAndIsOneLine() throws Exception {
        License l = new License(Type.TRIAL, "PT Antam – DBA Team", START, Type.TRIAL.lastDay(START));
        String key = sign(l);
        assertTrue(key.startsWith("DBA1-"));
        assertFalse(key.matches("(?s).*\\s.*"));
        assertTrue(key.length() < 160, key.length() + " characters");
        assertEquals(l, LicenseKey.verify(key, keys.getPublic()));
        // pasted with line breaks and spaces
        assertEquals(l, LicenseKey.verify("  " + key.substring(0, 40) + "\n" + key.substring(40) + " ", keys.getPublic()));
    }

    @Test
    void lifetimeHasNoEnd() throws Exception {
        License l = new License(Type.LIFETIME, "Hafiz Hisbullah", START, null);
        assertEquals(l, LicenseKey.verify(sign(l), keys.getPublic()));
        assertEquals(Status.VALID, l.status(START.plusYears(50)));
    }

    @Test
    void changedOrForeignKeysAreRejected() throws Exception {
        String key = sign(new License(Type.TRIAL, "PT Antam", START, Type.TRIAL.lastDay(START)));
        char last = key.charAt(20);
        String changed = key.substring(0, 20) + (last == 'A' ? 'B' : 'A') + key.substring(21);
        assertThrows(LicenseKey.InvalidKeyException.class, () -> LicenseKey.verify(changed, keys.getPublic()));
        assertThrows(LicenseKey.InvalidKeyException.class, () -> LicenseKey.verify(key, pair().getPublic()));
        assertThrows(LicenseKey.InvalidKeyException.class, () -> LicenseKey.verify("hello", keys.getPublic()));
        assertThrows(LicenseKey.InvalidKeyException.class, () -> LicenseKey.verify(key.substring(0, 50), keys.getPublic()));
    }

    @Test
    void lengthsOfEachType() {
        assertEquals(LocalDate.of(2026, 11, 21), Type.TRIAL.lastDay(START));
        assertEquals(LocalDate.of(2026, 11, 22), Type.MONTHLY.lastDay(START));
        assertEquals(LocalDate.of(2027, 10, 22), Type.YEARLY.lastDay(START));
    }

    @Test
    void trialEndsOnItsLastDayWithoutGrace() {
        License trial = new License(Type.TRIAL, "PT Antam", START, Type.TRIAL.lastDay(START));
        assertEquals(Status.VALID, trial.status(START));
        assertEquals(Status.EXPIRING, trial.status(trial.expires().minusDays(3)));
        assertTrue(trial.allows(trial.expires()));
        assertEquals(Status.EXPIRED, trial.status(trial.expires().plusDays(1)));
    }

    @Test
    void paidLicensesHaveAWeekOfGrace() {
        License monthly = new License(Type.MONTHLY, "PT Antam", START, Type.MONTHLY.lastDay(START));
        assertEquals(Status.GRACE, monthly.status(monthly.expires().plusDays(1)));
        assertEquals(Status.GRACE, monthly.status(monthly.expires().plusDays(License.GRACE_DAYS)));
        assertEquals(Status.EXPIRED, monthly.status(monthly.expires().plusDays(License.GRACE_DAYS + 1)));
    }

    @Test
    void installsKeysAndIgnoresAClockTurnedBack(@TempDir Path dir) throws Exception {
        Licensing licensing = new Licensing(dir, keys.getPublic());
        assertTrue(licensing.current().isEmpty());
        assertFalse(licensing.allowsCompare());
        assertThrows(LicenseKey.InvalidKeyException.class, () -> licensing.install("DBA1-nonsense"));

        LocalDate today = LocalDate.now();
        License l = new License(Type.TRIAL, "PT Antam", today, Type.TRIAL.lastDay(today));
        licensing.install(sign(l));
        assertEquals(l, licensing.current().orElseThrow());
        assertTrue(licensing.allowsCompare());

        // the app once ran 40 days from now: the trial stays ended even if the clock is back to today
        Files.writeString(dir.resolve("license-seen"), Long.toString(today.plusDays(40).toEpochDay()));
        assertEquals(today.plusDays(40), licensing.today());
        assertFalse(licensing.allowsCompare());
    }
}
