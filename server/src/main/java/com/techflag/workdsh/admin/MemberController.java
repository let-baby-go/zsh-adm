package com.techflag.workdsh.admin;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class MemberController {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final AuditLog audit;

    public MemberController(JdbcTemplate db, AuthService auth, AuditLog audit) { this.db = db; this.auth = auth; this.audit = audit; }

    public record Member(String id, String email, String displayName, String role, boolean active,
                         boolean mustChangePassword, int revision) {}
    public record NewMember(@Email @NotBlank String email, @NotBlank @Size(max=120) String displayName,
                            @NotBlank String role) {}
    public record CreatedMember(Member member, String temporaryPassword) {}
    public record UpdateMember(String role, Boolean active, int expectedRevision) {}

    @GetMapping("/members")
    public List<Member> directory(@RequestHeader(value="Authorization", required=false) String bearer) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        return db.query("select id,email,display_name,role,active,must_change_password,revision from members where organization_id=? and active=true and (not exists(select 1 from organization_policies where organization_id=members.organization_id and directory_scope='DEPARTMENT') or coalesce((select department_id from member_profiles where member_id=members.id),'')=coalesce((select department_id from member_profiles where member_id=?),'')) order by display_name",
                (rs,n)->new Member(rs.getString("id"),rs.getString("email"),rs.getString("display_name"),rs.getString("role"),rs.getBoolean("active"),rs.getBoolean("must_change_password"),rs.getInt("revision")),actor.organizationId(),actor.id());
    }

    public record MemberPage(List<Member> items, long total) {}
    public record MemberSummary(long total, long active, long admins) {}

    private AuthService.Actor admin(String bearer) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        return actor;
    }

    @GetMapping("/admin/members/summary")
    public MemberSummary summary(@RequestHeader(value="Authorization", required=false) String bearer) {
        return db.queryForObject("select count(*),coalesce(sum(case when active then 1 else 0 end),0),coalesce(sum(case when role<>'MEMBER' then 1 else 0 end),0) from members where organization_id=?",
                (rs, n) -> new MemberSummary(rs.getLong(1), rs.getLong(2), rs.getLong(3)), admin(bearer).organizationId());
    }

    /**
     * One page of members ordered by name. {@code q} matches name, email, login username or employee number;
     * {@code departments} limits to those primary departments (the caller expands sub-departments);
     * {@code unassigned} selects members without a primary department.
     */
    @GetMapping("/admin/members/search")
    public MemberPage search(@RequestHeader(value="Authorization", required=false) String bearer,
                             @RequestParam(defaultValue="") String q,
                             @RequestParam(defaultValue="all") String status,
                             @RequestParam(required=false) List<String> departments,
                             @RequestParam(defaultValue="false") boolean unassigned,
                             @RequestParam(defaultValue="0") int offset,
                             @RequestParam(defaultValue="50") int limit) {
        var actor = admin(bearer);
        if (offset < 0 || limit < 1 || limit > 100 || q.length() > 128 || !List.of("all", "active", "disabled").contains(status)
                || (departments != null && departments.size() > 1000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid member query");
        }
        StringBuilder where = new StringBuilder(" from members m left join member_profiles p on p.member_id=m.id where m.organization_id=?");
        List<Object> args = new java.util.ArrayList<>(List.of(actor.organizationId()));
        if (!status.equals("all")) { where.append(" and m.active=?"); args.add(status.equals("active")); }
        if (!q.isBlank()) {
            String pattern = "%" + q.trim().toLowerCase(Locale.ROOT).replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%";
            where.append(" and (lower(m.display_name) like ? escape '!' or lower(m.email) like ? escape '!'"
                    + " or lower(coalesce(p.username,'')) like ? escape '!' or lower(coalesce(p.employee_number,'')) like ? escape '!')");
            for (int i = 0; i < 4; i++) args.add(pattern);
        }
        if (unassigned) where.append(" and (p.department_id is null or p.department_id='')");
        else if (departments != null) {
            if (departments.isEmpty()) return new MemberPage(List.of(), 0);
            where.append(" and p.department_id in (").append(String.join(",", java.util.Collections.nCopies(departments.size(), "?"))).append(")");
            args.addAll(departments);
        }
        long total = db.queryForObject("select count(*)" + where, Long.class, args.toArray());
        List<Object> pageArgs = new java.util.ArrayList<>(args);
        pageArgs.add(limit);
        pageArgs.add(offset);
        var items = db.query("select m.id,m.email,m.display_name,m.role,m.active,m.must_change_password,m.revision" + where
                        + " order by m.display_name,m.id limit ? offset ?",
                (rs, n) -> new Member(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getBoolean(5), rs.getBoolean(6), rs.getInt(7)), pageArgs.toArray());
        return new MemberPage(items, total);
    }

    @PostMapping("/admin/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreatedMember create(@RequestHeader(value="Authorization", required=false) String bearer,
                                @Valid @RequestBody NewMember request) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        if (!List.of("ADMIN", "MEMBER").contains(request.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        if (request.role().equals("ADMIN") && !actor.role().equals("OWNER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner may appoint admins");
        String id = UUID.randomUUID().toString();
        String temporaryPassword = auth.newTemporaryPassword();
        try {
            db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,true,true,1)",
                    id, actor.organizationId(), request.email().trim().toLowerCase(Locale.ROOT),
                    request.displayName().trim(), request.role(), auth.encode(temporaryPassword));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Member email already exists");
        }
        audit.record(actor, "member.created", id);
        return new CreatedMember(new Member(id, request.email().trim().toLowerCase(Locale.ROOT),
                request.displayName().trim(), request.role(), true, true, 1), temporaryPassword);
    }

    @PatchMapping("/admin/members/{id}")
    @Transactional
    public Member update(@RequestHeader(value="Authorization", required=false) String bearer,
                         @PathVariable String id, @RequestBody UpdateMember change) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        Member before = member(actor.organizationId(), id);
        if (before.role().equals("OWNER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner cannot be changed here");
        }
        if (!actor.role().equals("OWNER") && (before.role().equals("ADMIN") || "ADMIN".equals(change.role()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner may manage admins");
        String role = change.role() == null ? before.role() : change.role();
        if (!List.of("ADMIN", "MEMBER").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        boolean active = change.active() == null ? before.active() : change.active();
        if (id.equals(actor.id()) && !active) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot disable yourself");
        }
        int changed = db.update("update members set role=?,active=?,revision=revision+1 where id=? and organization_id=? and revision=?",
                role, active, id, actor.organizationId(), change.expectedRevision());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Member changed; refresh first");
        if (!active) {
            db.update("delete from auth_sessions where member_id=?", id);
        }
        audit.record(actor, active ? "member.updated" : "member.disabled", id);
        return member(actor.organizationId(), id);
    }

    /** Newest first, at most 100 entries per page. */
    @GetMapping("/admin/audit")
    public List<AuditLog.Entry> auditList(@RequestHeader(value="Authorization", required=false) String bearer,
                                          @RequestParam(defaultValue="0") int offset,
                                          @RequestParam(defaultValue="50") int limit) {
        var actor = admin(bearer);
        if (offset < 0 || limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid page");
        return audit.page(actor.organizationId(), offset, limit);
    }

    private Member member(String org, String id) {
        return db.query("select id,email,display_name,role,active,must_change_password,revision from members where organization_id=? and id=?",
                (rs, n) -> new Member(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("role"), rs.getBoolean("active"), rs.getBoolean("must_change_password"),
                        rs.getInt("revision")), org, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
    }
}
