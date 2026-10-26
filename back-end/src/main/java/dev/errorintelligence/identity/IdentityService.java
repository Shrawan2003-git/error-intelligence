package dev.errorintelligence.identity;

import static dev.errorintelligence.identity.IdentityDtos.*;

import dev.errorintelligence.common.api.DomainException;
import dev.errorintelligence.common.security.Credentials;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class IdentityService {
  private final UserRepository users;
  private final JdbcTemplate jdbc;
  private final PasswordEncoder passwords;
  private final JwtEncoder encoder;
  private final AuditWriter audit;
  private final AccountRecoveryService recovery;
  private final String issuer;
  private final String dummyHash;

  public IdentityService(
      UserRepository users,
      JdbcTemplate jdbc,
      PasswordEncoder passwords,
      JwtEncoder encoder,
      AuditWriter audit,
      AccountRecoveryService recovery,
      @Value("${app.auth.issuer}") String issuer) {
    this.users = users;
    this.jdbc = jdbc;
    this.passwords = passwords;
    this.encoder = encoder;
    this.audit = audit;
    this.recovery = recovery;
    this.issuer = issuer;
    this.dummyHash = passwords.encode(Credentials.randomToken());
  }

  public static String normalizeEmail(String email) {
    return email.strip().toLowerCase(Locale.ROOT);
  }

  @Transactional
  public void register(Registration input) {
    AccountRecoveryService.validatePassword(input.password());
    if (users.findByEmail(normalizeEmail(input.email())).isPresent()) return;
    UserAccount user =
        users.saveAndFlush(
            new UserAccount(
                normalizeEmail(input.email()),
                input.displayName().strip(),
                passwords.encode(input.password())));
    UUID org = createOrganization(user.id(), input.organizationName());
    recovery.issueVerification(user);
  }

  @Transactional
  public IssuedSession login(Login input) {
    Optional<UserAccount> found = users.findByEmail(normalizeEmail(input.email()));
    if (input.password().getBytes(StandardCharsets.UTF_8).length > 72)
      throw new DomainException(
          HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
    boolean matches =
        passwords.matches(input.password(), found.map(UserAccount::passwordHash).orElse(dummyHash));
    if (!matches || found.isEmpty())
      throw new DomainException(
          HttpStatus.UNAUTHORIZED, "INVALID_CREDENTIALS", "Email or password is incorrect.");
    UserAccount user = found.get();
    if (user.emailVerifiedAt() == null)
      throw new DomainException(
          HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED", "Verify your email before signing in.");
    List<Organization> memberships = organizations(user.id());
    if (memberships.isEmpty())
      throw new DomainException(
          HttpStatus.FORBIDDEN, "NO_MEMBERSHIP", "This account has no organization membership.");
    return newSession(user, memberships.getFirst().id());
  }

  private IssuedSession newSession(UserAccount user, UUID organizationId) {
    UUID session = UUID.randomUUID();
    Instant expires = Instant.now().plus(30, ChronoUnit.DAYS);
    jdbc.update(
        "insert into intelligence.sessions(id,user_id,organization_id,expires_at) values(?,?,?,?)",
        session,
        user.id(),
        organizationId,
        Timestamp.from(expires));
    audit.record(organizationId, user.id(), "SESSION_CREATED", session);
    return issue(user, session, organizationId, expires);
  }

  private IssuedSession issue(
      UserAccount user, UUID session, UUID organizationId, Instant expires) {
    String refresh = Credentials.randomToken();
    jdbc.update(
        "insert into intelligence.refresh_tokens(id,session_id,digest,expires_at) values(?,?,?,?)",
        UUID.randomUUID(),
        session,
        Credentials.digest(refresh),
        Timestamp.from(expires));
    return new IssuedSession(response(user, session, organizationId), refresh);
  }

  private SessionResponse response(UserAccount user, UUID session, UUID org) {
    Instant now = Instant.now();
    JwtClaimsSet claims =
        JwtClaimsSet.builder()
            .issuer(issuer)
            .audience(List.of("error-intelligence-api"))
            .subject(user.id().toString())
            .issuedAt(now)
            .expiresAt(now.plusSeconds(600))
            .id(UUID.randomUUID().toString())
            .claim("sid", session.toString())
            .claim("org", org.toString())
            .build();
    String access =
        encoder
            .encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
            .getTokenValue();
    return new SessionResponse(access, 600, account(user, org));
  }

  @Transactional(noRollbackFor = DomainException.class)
  public IssuedSession refresh(String token) {
    if (token == null || token.length() != 43) throw DomainException.unauthenticated();
    String digest = Credentials.digest(token);
    UUID session =
        jdbc
            .query(
                "select session_id from intelligence.refresh_tokens where digest=?",
                (rs, n) -> rs.getObject(1, UUID.class),
                digest)
            .stream()
            .findFirst()
            .orElseThrow(DomainException::unauthenticated);
    var sessions =
        jdbc.query(
            "select user_id,organization_id,expires_at,revoked_at from intelligence.sessions where id=? for update",
            (rs, n) ->
                new SessionRow(
                    rs.getObject(1, UUID.class),
                    rs.getObject(2, UUID.class),
                    rs.getTimestamp(3).toInstant(),
                    rs.getTimestamp(4) != null),
            session);
    if (sessions.isEmpty()) throw DomainException.unauthenticated();
    SessionRow row = sessions.getFirst();
    Boolean consumed =
        jdbc.queryForObject(
            "select consumed_at is not null from intelligence.refresh_tokens where digest=?",
            Boolean.class,
            digest);
    if (Boolean.TRUE.equals(consumed)) {
      jdbc.update("update intelligence.sessions set revoked_at=now() where id=?", session);
      audit.record(row.org(), row.user(), "REFRESH_REUSE_REVOKED", session);
      throw DomainException.unauthenticated();
    }
    if (row.revoked()
        || !row.expires().isAfter(Instant.now())
        || users.findById(row.user()).filter(user -> user.emailVerifiedAt() != null).isEmpty()
        || organizations(row.user()).stream().noneMatch(org -> org.id().equals(row.org())))
      throw DomainException.unauthenticated();
    jdbc.update("update intelligence.refresh_tokens set consumed_at=now() where digest=?", digest);
    return issue(
        users.findById(row.user()).orElseThrow(DomainException::unauthenticated),
        session,
        row.org(),
        row.expires());
  }

  @Transactional
  public void logout(String token) {
    if (token == null || token.length() != 43) return;
    jdbc.query(
        "select s.id,s.user_id,s.organization_id from intelligence.sessions s join intelligence.refresh_tokens r on r.session_id=s.id where r.digest=? for update of s",
        (rs, n) -> {
          UUID id = rs.getObject(1, UUID.class);
          jdbc.update("update intelligence.sessions set revoked_at=now() where id=?", id);
          audit.record(
              rs.getObject(3, UUID.class), rs.getObject(2, UUID.class), "SESSION_LOGOUT", id);
          return id;
        },
        Credentials.digest(token));
  }

  public Tenant tenant(Jwt jwt) {
    if (jwt == null) throw DomainException.unauthenticated();
    UUID user = UUID.fromString(jwt.getSubject()),
        session = UUID.fromString(jwt.getClaimAsString("sid")),
        org = UUID.fromString(jwt.getClaimAsString("org"));
    return jdbc
        .query(
            "select m.role from intelligence.memberships m join intelligence.sessions s on s.user_id=m.user_id and s.organization_id=m.organization_id join intelligence.users u on u.id=s.user_id and u.email_verified_at is not null where s.id=? and s.user_id=? and s.organization_id=? and s.revoked_at is null and s.expires_at>now()",
            (rs, n) -> new Tenant(user, session, org, Role.valueOf(rs.getString(1))),
            session,
            user,
            org)
        .stream()
        .findFirst()
        .orElseThrow(DomainException::unauthenticated);
  }

  public Account me(Tenant tenant) {
    return account(
        users.findById(tenant.userId()).orElseThrow(DomainException::unauthenticated),
        tenant.organizationId());
  }

  private Account account(UserAccount user, UUID org) {
    return new Account(user.id(), user.email(), user.displayName(), organizations(user.id()), org);
  }

  private List<Organization> organizations(UUID user) {
    return jdbc.query(
        "select o.id,o.name,m.role from intelligence.organizations o join intelligence.memberships m on m.organization_id=o.id where m.user_id=? order by o.created_at,o.id",
        (rs, n) ->
            new Organization(
                rs.getObject(1, UUID.class), rs.getString(2), Role.valueOf(rs.getString(3))),
        user);
  }

  @Transactional
  public SessionResponse switchOrganization(Tenant tenant, UUID organizationId) {
    jdbc.queryForObject(
        "select id from intelligence.sessions where id=? for update",
        UUID.class,
        tenant.sessionId());
    if (organizations(tenant.userId()).stream().noneMatch(org -> org.id().equals(organizationId)))
      throw DomainException.missing();
    jdbc.update(
        "update intelligence.sessions set organization_id=? where id=?",
        organizationId,
        tenant.sessionId());
    return response(
        users.findById(tenant.userId()).orElseThrow(DomainException::unauthenticated),
        tenant.sessionId(),
        organizationId);
  }

  @Transactional
  public Organization addOrganization(Tenant tenant, String name) {
    UUID id = createOrganization(tenant.userId(), name);
    return new Organization(id, name.strip(), Role.OWNER);
  }

  private UUID createOrganization(UUID user, String name) {
    UUID id = UUID.randomUUID();
    jdbc.update("insert into intelligence.organizations(id,name) values(?,?)", id, name.strip());
    jdbc.update(
        "insert into intelligence.memberships(organization_id,user_id,role) values(?,?,'OWNER')",
        id,
        user);
    audit.record(id, user, "ORGANIZATION_CREATED", id);
    return id;
  }

  public List<Member> members(Tenant tenant, UUID org, int offset) {
    scope(tenant, org);
    return jdbc.query(
        "select u.id,u.display_name,u.email,m.role from intelligence.memberships m join intelligence.users u on u.id=m.user_id where m.organization_id=? order by u.display_name,u.id limit 100 offset ?",
        (rs, n) ->
            new Member(
                rs.getObject(1, UUID.class),
                rs.getString(2),
                rs.getString(3),
                Role.valueOf(rs.getString(4))),
        org,
        offset);
  }

  @Transactional
  public void addMember(Tenant tenant, UUID org, MemberInput input) {
    lockOrganization(tenant, org);
    tenant.requireAdmin();
    if (input.role() == Role.OWNER && tenant.role() != Role.OWNER) throw DomainException.denied();
    UserAccount user =
        users
            .findByEmail(normalizeEmail(input.email()))
            .orElseThrow(
                () ->
                    new DomainException(
                        HttpStatus.NOT_FOUND,
                        "ACCOUNT_NOT_FOUND",
                        "The account must register before it can be added."));
    jdbc.update(
        "insert into intelligence.memberships(organization_id,user_id,role) values(?,?,?)",
        org,
        user.id(),
        input.role().name());
    audit.record(org, tenant.userId(), "MEMBER_ADDED", user.id());
  }

  @Transactional
  public void changeMember(Tenant tenant, UUID org, UUID user, Role next) {
    lockOrganization(tenant, org);
    tenant.requireAdmin();
    Role old =
        jdbc
            .query(
                "select role from intelligence.memberships where organization_id=? and user_id=?",
                (rs, n) -> Role.valueOf(rs.getString(1)),
                org,
                user)
            .stream()
            .findFirst()
            .orElseThrow(DomainException::missing);
    if ((old == Role.OWNER || next == Role.OWNER) && tenant.role() != Role.OWNER)
      throw DomainException.denied();
    if (old == Role.OWNER
        && next != Role.OWNER
        && jdbc.queryForObject(
                "select count(*) from intelligence.memberships where organization_id=? and role='OWNER'",
                Integer.class,
                org)
            == 1)
      throw new DomainException(
          HttpStatus.CONFLICT, "LAST_OWNER", "An organization must retain an owner.");
    if (next == null)
      jdbc.update(
          "delete from intelligence.memberships where organization_id=? and user_id=?", org, user);
    else
      jdbc.update(
          "update intelligence.memberships set role=? where organization_id=? and user_id=?",
          next.name(),
          org,
          user);
    audit.record(
        org, tenant.userId(), next == null ? "MEMBER_REMOVED" : "MEMBER_ROLE_CHANGED", user);
  }

  private void lockOrganization(Tenant tenant, UUID org) {
    scope(tenant, org);
    jdbc.queryForObject(
        "select id from intelligence.organizations where id=? for update", UUID.class, org);
  }

  private void scope(Tenant tenant, UUID org) {
    if (!tenant.organizationId().equals(org)) throw DomainException.missing();
  }

  private record SessionRow(UUID user, UUID org, Instant expires, boolean revoked) {}
}
