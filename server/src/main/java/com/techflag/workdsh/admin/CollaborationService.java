package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Member-to-member handoffs, replies and notifications. Callers pass an authenticated, ready actor; every query is
 * scoped to the actor's organization and to handoffs the actor sends or receives.
 */
@Service
public class CollaborationService {
    public record Colleague(String id, String displayName, String email) {}
    public record Handoff(String id, String senderId, String senderName, String recipientId,
                          String recipientName, String summary, String status, String resolution,
                          Instant createdAt, Instant completedAt, Instant lastMessageAt, boolean needsReply) {}
    public record NewHandoff(@NotBlank String recipientId,
                             @NotBlank @Size(max=2000) String summary,
                             @NotBlank @Size(max=128) String requestKey) {}
    public record Completion(@NotBlank @Size(max=2000) String resolution) {}
    public record MessageEntry(String id, String handoffId, String authorId, String authorName,
                               String content, Instant createdAt) {}
    public record NewMessage(@NotBlank @Size(max=2000) String content,
                             @NotBlank @Size(max=128) String requestKey) {}
    public record NotificationEntry(String id, String handoffId, String authorName, String kind,
                                    String preview, Instant createdAt) {}

    private final JdbcTemplate db;
    private final DatabaseDialect dialect;
    private final AuditLog audit;

    public CollaborationService(JdbcTemplate db, DatabaseDialect dialect, AuditLog audit) {
        this.db = db;
        this.dialect = dialect;
        this.audit = audit;
    }

    public List<NotificationEntry> notifications(AuthService.Actor actor) {
        return db.query("""
                select n.id,n.handoff_id,m.display_name,n.kind,n.preview,n.created_at
                from collaboration_notifications n join members m on m.id=n.author_id
                where n.organization_id=? and n.recipient_id=? and n.read_at is null
                order by n.created_at,n.id limit 100
                """, (rs,row) -> new NotificationEntry(rs.getString("id"),rs.getString("handoff_id"),
                rs.getString("display_name"),rs.getString("kind"),rs.getString("preview"),
                rs.getTimestamp("created_at").toInstant()), actor.organizationId(),actor.id());
    }

    public void readNotification(AuthService.Actor actor, String id) {
        int changed = db.update("update collaboration_notifications set read_at=coalesce(read_at,?) where id=? and organization_id=? and recipient_id=?",
                Timestamp.from(Instant.now()),id,actor.organizationId(),actor.id());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Notification not found");
    }

    public List<Colleague> colleagues(AuthService.Actor actor) {
        return db.query("select id,display_name,email from members where organization_id=? and active=true and must_change_password=false and id<>? and (not exists(select 1 from organization_policies where organization_id=members.organization_id and directory_scope='DEPARTMENT') or coalesce((select department_id from member_profiles where member_id=members.id),'')=coalesce((select department_id from member_profiles where member_id=?),'')) order by display_name,email",
                (rs, n) -> new Colleague(rs.getString("id"), rs.getString("display_name"), rs.getString("email")), actor.organizationId(), actor.id(),actor.id());
    }

    public List<Handoff> inbox(AuthService.Actor actor) { return list("h.recipient_id=?", actor); }

    public List<Handoff> sent(AuthService.Actor actor) { return list("h.sender_id=?", actor); }

    /** Idempotent per sender and request key: a retry with the same recipient and summary returns the original. */
    @Transactional
    public Handoff send(AuthService.Actor actor, NewHandoff input) {
        // Serialize sends by this member so two retries with one request key observe the same result.
        var sender = db.query(dialect.lock("select id from members where id=? and organization_id=? and active=true"),
                (rs, row) -> rs.getString("id"), actor.id(), actor.organizationId());
        if (sender.isEmpty()) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Member is inactive");
        String recipient = input.recipientId().trim();
        if (recipient.equals(actor.id()) || !recipient.matches("[0-9a-fA-F-]{36}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another active colleague");
        }
        Integer count = db.queryForObject("select count(*) from members where id=? and organization_id=? and active=true and must_change_password=false",
                Integer.class, recipient, actor.organizationId());
        if (count == null || count != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another active colleague");
        requireSharingAllowed(actor, recipient);
        String summary = input.summary().trim();
        String key = input.requestKey().trim();
        if (summary.isEmpty() || key.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Summary and request key required");
        var previous = db.query("select id,recipient_id,summary from collaboration_handoffs where sender_id=? and request_key=?",
                (rs,n) -> new String[]{rs.getString("id"),rs.getString("recipient_id"),rs.getString("summary")}, actor.id(), key);
        if (!previous.isEmpty()) {
            if (!previous.get(0)[1].equals(recipient) || !previous.get(0)[2].equals(summary))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request key already used");
            return detail(actor, previous.get(0)[0]);
        }
        String id = UUID.randomUUID().toString();
        try {
            db.update("insert into collaboration_handoffs(id,organization_id,sender_id,recipient_id,request_key,summary,status,created_at) values (?,?,?,?,?,?,?,?)",
                    id, actor.organizationId(), actor.id(), recipient, key, summary, "OPEN", Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request key already used");
        }
        audit.record(actor, "collaboration.handoff.sent", id);
        notifyMember(actor,recipient,id,"HANDOFF",summary);
        return detail(actor, id);
    }

    private void requireSharingAllowed(AuthService.Actor actor, String recipient) {
        var policy = db.query("select directory_scope,sharing_enabled from organization_policies where organization_id=?",
                (rs,n)->new Object[]{rs.getString(1),rs.getBoolean(2)}, actor.organizationId());
        if (policy.isEmpty()) return;
        if (!((Boolean) policy.get(0)[1])) throw new ResponseStatusException(HttpStatus.FORBIDDEN,"企业已关闭新的协作分享");
        if (policy.get(0)[0].equals("DEPARTMENT")) {
            var own = db.queryForList("select coalesce(department_id,'') from member_profiles where member_id=?", String.class, actor.id());
            var target = db.queryForList("select coalesce(department_id,'') from member_profiles where member_id=?", String.class, recipient);
            if (!java.util.Objects.equals(own.isEmpty() ? "" : own.get(0), target.isEmpty() ? "" : target.get(0)))
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,"通讯录范围不允许跨部门分享");
        }
    }

    /** Idempotent: completing again with the same resolution returns the completed handoff. */
    @Transactional
    public Handoff complete(AuthService.Actor actor, String id, Completion input) {
        if (input.resolution().trim().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resolution required");
        int changed = db.update("update collaboration_handoffs set status='DONE',resolution=?,completed_at=? where id=? and organization_id=? and recipient_id=? and status='OPEN'",
                input.resolution().trim(), Timestamp.from(Instant.now()), id, actor.organizationId(), actor.id());
        if (changed != 1) {
            var previous = db.query("select resolution from collaboration_handoffs where id=? and organization_id=? and recipient_id=? and status='DONE'",
                    (rs, row) -> rs.getString("resolution"), id, actor.organizationId(), actor.id());
            if (previous.size() == 1 && input.resolution().trim().equals(previous.get(0))) return detail(actor, id);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Handoff unavailable or already completed");
        }
        audit.record(actor, "collaboration.handoff.completed", id);
        notifyMember(actor,detail(actor, id).senderId(),id,"COMPLETED",input.resolution().trim());
        return detail(actor, id);
    }

    public List<MessageEntry> messages(AuthService.Actor actor, String id) {
        detail(actor, id);
        return db.query("""
                select m.id,m.handoff_id,m.author_id,a.display_name as author_name,m.content,m.created_at
                from collaboration_messages m join members a on a.id=m.author_id
                where m.handoff_id=? and m.organization_id=? order by m.created_at,m.id
                """, (rs, row) -> message(rs), id, actor.organizationId());
    }

    /** Idempotent per author and request key. */
    @Transactional
    public MessageEntry reply(AuthService.Actor actor, String id, NewMessage input) {
        String content = input.content().trim();
        String key = input.requestKey().trim();
        if (content.isEmpty() || key.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message and request key required");
        var handoffs = db.query(dialect.lock("""
                select status from collaboration_handoffs where id=? and organization_id=?
                and (sender_id=? or recipient_id=?)
                """), (rs, row) -> rs.getString("status"), id, actor.organizationId(), actor.id(), actor.id());
        if (handoffs.size() != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Handoff not found");
        var previous = db.query("select id,content from collaboration_messages where handoff_id=? and author_id=? and request_key=?",
                (rs, row) -> new String[]{rs.getString("id"), rs.getString("content")}, id, actor.id(), key);
        if (!previous.isEmpty()) {
            if (!content.equals(previous.get(0)[1])) throw new ResponseStatusException(HttpStatus.CONFLICT, "Request key already used");
            return oneMessage(previous.get(0)[0]);
        }
        if (!"OPEN".equals(handoffs.get(0))) throw new ResponseStatusException(HttpStatus.CONFLICT, "Handoff is complete");
        String messageId = UUID.randomUUID().toString();
        db.update("insert into collaboration_messages(id,handoff_id,organization_id,author_id,request_key,content,created_at) values (?,?,?,?,?,?,?)",
                messageId, id, actor.organizationId(), actor.id(), key, content, Timestamp.from(Instant.now()));
        audit.record(actor, "collaboration.handoff.message", id);
        var handoff = detail(actor, id);
        notifyMember(actor,actor.id().equals(handoff.senderId()) ? handoff.recipientId() : handoff.senderId(),id,"REPLY",content);
        return oneMessage(messageId);
    }

    /** One handoff the actor sends or receives; 404 otherwise. Also the access check for shared materials. */
    public Handoff detail(AuthService.Actor actor, String id) {
        return db.query("""
                select h.id,h.sender_id,s.display_name as sender_name,h.recipient_id,r.display_name as recipient_name,
                       h.summary,h.status,h.resolution,h.created_at,h.completed_at,
                       (select max(m.created_at) from collaboration_messages m where m.handoff_id=h.id) as last_message_at,
                       (select m.author_id from collaboration_messages m where m.handoff_id=h.id order by m.created_at desc,m.id desc limit 1) as last_message_author_id
                from collaboration_handoffs h
                join members s on s.id=h.sender_id join members r on r.id=h.recipient_id
                where h.id=? and h.organization_id=? and (h.sender_id=? or h.recipient_id=?)
                """, (rs,n) -> handoff(rs, actor.id()), id, actor.organizationId(), actor.id(), actor.id())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Handoff not found"));
    }

    private void notifyMember(AuthService.Actor actor,String recipient,String handoffId,String kind,String content) {
        db.update("insert into collaboration_notifications(id,organization_id,recipient_id,author_id,handoff_id,kind,preview,created_at) values (?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(),actor.organizationId(),recipient,actor.id(),handoffId,kind,
                content.substring(0,Math.min(content.length(),240)),Timestamp.from(Instant.now()));
    }

    private List<Handoff> list(String side, AuthService.Actor actor) {
        String activity = "case when h.status='DONE' then h.completed_at else coalesce((select max(m.created_at) from collaboration_messages m where m.handoff_id=h.id),h.created_at) end";
        String lastAuthor = "coalesce((select m.author_id from collaboration_messages m where m.handoff_id=h.id order by m.created_at desc,m.id desc limit 1),h.sender_id)";
        String ordering = "case when h.status='OPEN' and " + lastAuthor + "<>? then 0 else 1 end, "
                + activity + " desc, h.created_at desc";
        return db.query("""
                select h.id,h.sender_id,s.display_name as sender_name,h.recipient_id,r.display_name as recipient_name,
                       h.summary,h.status,h.resolution,h.created_at,h.completed_at,
                       (select max(m.created_at) from collaboration_messages m where m.handoff_id=h.id) as last_message_at,
                       (select m.author_id from collaboration_messages m where m.handoff_id=h.id order by m.created_at desc,m.id desc limit 1) as last_message_author_id
                from collaboration_handoffs h
                join members s on s.id=h.sender_id join members r on r.id=h.recipient_id
                where h.organization_id=? and """ + " " + side + " order by " + ordering + " limit 100",
                (rs,n) -> handoff(rs, actor.id()), actor.organizationId(), actor.id(), actor.id());
    }

    private Handoff handoff(java.sql.ResultSet rs, String actorId) throws java.sql.SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        Timestamp lastMessage = rs.getTimestamp("last_message_at");
        String lastAuthor = rs.getString("last_message_author_id");
        boolean needsReply = "OPEN".equals(rs.getString("status"))
                && (lastAuthor == null ? actorId.equals(rs.getString("recipient_id")) : !actorId.equals(lastAuthor));
        return new Handoff(rs.getString("id"), rs.getString("sender_id"), rs.getString("sender_name"),
                rs.getString("recipient_id"), rs.getString("recipient_name"), rs.getString("summary"),
                rs.getString("status"), rs.getString("resolution"), rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant(),
                lastMessage == null ? null : lastMessage.toInstant(), needsReply);
    }

    private MessageEntry oneMessage(String id) {
        return db.query("""
                select m.id,m.handoff_id,m.author_id,a.display_name as author_name,m.content,m.created_at
                from collaboration_messages m join members a on a.id=m.author_id where m.id=?
                """, (rs, row) -> message(rs), id).get(0);
    }

    private MessageEntry message(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new MessageEntry(rs.getString("id"), rs.getString("handoff_id"), rs.getString("author_id"),
                rs.getString("author_name"), rs.getString("content"), rs.getTimestamp("created_at").toInstant());
    }
}
