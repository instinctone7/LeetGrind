package com.leetgrind.identity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import com.leetgrind.platform.Utc;
import com.leetgrind.progress.ProgressService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import com.leetgrind.integration.LinkService;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class IdentityService {
    private static final Duration SESSION_LIFETIME = Duration.ofDays(14);
    private static final Duration ACCESS_LIFETIME = Duration.ofMinutes(10);
    private final AccountRepository accounts;
    private final SignupPolicy signup;
    private final ProgressService progress;
    private final LinkService links;
    private final SessionFamilyRepository families;
    private final RefreshSessionRepository refreshSessions;
    private final PasswordEncoder passwords;
    private final JwtEncoder jwtEncoder;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final String dummyHash;

    IdentityService(AccountRepository accounts, SignupPolicy signup, ProgressService progress, LinkService links,
                    SessionFamilyRepository families,
                    RefreshSessionRepository refreshSessions, PasswordEncoder passwords,
                    JwtEncoder jwtEncoder, Clock clock) {
        this.accounts = accounts;
        this.signup = signup;
        this.progress = progress;
        this.links = links;
        this.families = families;
        this.refreshSessions = refreshSessions;
        this.passwords = passwords;
        this.jwtEncoder = jwtEncoder;
        this.clock = clock;
        this.dummyHash = passwords.encode(UUID.randomUUID().toString());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Profile register(String email, String name, String password, String leetcodeHandle) {
        signup.check(email);
        var account = new Account(normalize(email), newHandle(name), name.strip(), passwords.encode(password));
        account = accounts.saveAndFlush(account);
        links.savePublicHandle(account.id(), leetcodeHandle);
        return profile(account);
    }

    private String newHandle(String name) {
        String prefix = normalize(name).replaceAll("[^a-z0-9]+", "_").replaceAll("^_|_$", "");
        if (prefix.isEmpty()) prefix = "member";
        byte[] suffix = new byte[8];
        random.nextBytes(suffix);
        return prefix.substring(0, Math.min(prefix.length(), 15)) + "_" + HexFormat.of().formatHex(suffix);
    }

    @Transactional
    public Credentials login(String email, String password) {
        Account account = accounts.findByEmail(normalize(email)).orElse(null);
        String hash = account == null ? dummyHash : account.passwordHash();
        if (!passwords.matches(password, hash) || account == null || !account.active()) {
            return null;
        }

        account = accounts.lockActiveAccount(account.id()).orElse(null);
        if (account == null) {
            return null;
        }
        Instant now = Utc.now(clock);
        progress.creditLogin(account.id(), now);
        var family = families.save(new SessionFamily(account, now.plus(SESSION_LIFETIME)));
        return issue(family, now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Credentials refresh(String rawToken) {
        LockedSession locked = lockSession(rawToken);
        if (locked == null) {
            return null;
        }

        Instant now = clock.instant();
        SessionFamily family = locked.family();
        if (!family.activeAt(now) || !family.account().active()) {
            return null;
        }
        if (locked.token().used()) {
            family.revoke(now);
            return null;
        }
        locked.token().use(now);
        return issue(family, now);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public void logout(String rawToken) {
        LockedSession locked = lockSession(rawToken);
        if (locked != null) {
            locked.family().revoke(clock.instant());
        }
    }

    @Transactional(readOnly = true)
    public Profile getProfile(long id) {
        return accounts.findById(id).filter(Account::active).map(IdentityService::profile).orElse(null);
    }

    @Transactional
    public Profile updateProfile(long id, String displayName) {
        return accounts.lockActiveAccount(id).map(account -> {
            account.rename(displayName.strip());
            return profile(account);
        }).orElse(null);
    }

    private LockedSession lockSession(String rawToken) {
        if (rawToken == null || !rawToken.matches("[A-Za-z0-9_-]{43}")) {
            return null;
        }
        String digest = digest(rawToken);
        // Lock account before family so refresh and account erasure serialize.
        return refreshSessions.findAccountId(digest).flatMap(accounts::lockActiveAccount)
                .flatMap(account -> refreshSessions.findFamilyId(digest)).flatMap(families::lock)
                .flatMap(family -> refreshSessions.findById(digest).map(token -> new LockedSession(family, token)))
                .orElse(null);
    }

    private Credentials issue(SessionFamily family, Instant now) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        refreshSessions.save(new RefreshSession(digest(refreshToken), family));

        Instant expiration = now.plus(ACCESS_LIFETIME);
        if (expiration.isAfter(family.expiresAt())) {
            expiration = family.expiresAt();
        }
        String accountId = Long.toString(family.account().id());
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(IdentityConfiguration.ISSUER)
                .audience(List.of(IdentityConfiguration.AUDIENCE))
                .subject(accountId)
                .issuedAt(now).expiresAt(expiration)
                .id(UUID.randomUUID().toString())
                .claim("sid", family.id())
                .build();
        var header = JwsHeader.with(MacAlgorithm.HS256).build();
        String accessToken = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new Credentials(accountId, accessToken, refreshToken,
                expiresIn(now, expiration), expiresIn(now, family.expiresAt()));
    }

    private static long expiresIn(Instant now, Instant expiresAt) {
        return Math.max(0, expiresAt.getEpochSecond() - now.getEpochSecond());
    }

    private static String digest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }

    private static Profile profile(Account account) {
        return new Profile(Long.toString(account.id()), account.email(), account.handle(), account.displayName());
    }

    record Profile(String id, String email, String handle, String displayName) {}
    record Credentials(String accountId, String accessToken, String refreshToken, long accessSeconds, long refreshSeconds) {}
    private record LockedSession(SessionFamily family, RefreshSession token) {}
}
