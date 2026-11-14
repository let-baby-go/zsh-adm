package com.techflag.workdsh.admin;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;

/** Server-side member search/paging and audit paging, on SQLite and on H2 (PostgreSQL mode). */
class MemberPagingTest {

    abstract static class Scenario {
        @Autowired MemberController members;
        @Autowired AuthService auth;
        @Autowired JdbcTemplate db;
        @Autowired AuditLog audit;

        abstract String admin();

        @Test
        void searchFiltersAndPagesOnTheServer() {
            String bearer = "Bearer " + auth.login(admin(), "PagingPassword123!").token();
            var actor = auth.actor(bearer);
            String org = actor.organizationId(), sales = UUID.randomUUID().toString(), east = UUID.randomUUID().toString();
            db.update("insert into departments(id,organization_id,parent_id,name,revision) values(?,?,?,?,1)", sales, org, null, "销售");
            db.update("insert into departments(id,organization_id,parent_id,name,revision) values(?,?,?,?,1)", east, org, sales, "华东");
            for (int i = 0; i < 25; i++) {
                String id = UUID.randomUUID().toString();
                db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,false,?,1)",
                        id, org, "user" + i + "_" + org.substring(0, 8) + "@example.test", String.format("成员%02d", i), "MEMBER", "x", i % 5 != 0);
                String department = i < 10 ? sales : i < 15 ? east : null;
                db.update("insert into member_profiles(member_id,organization_id,department_id,username,employee_number,job_title) values(?,?,?,?,?,?)",
                        id, org, department, i == 7 ? "zhang_san" : null, "E" + i, null);
            }

            var first = members.search(bearer, "", "all", null, false, 0, 10);
            assertEquals(26, first.total(), "25 members plus the owner");
            assertEquals(10, first.items().size());
            var last = members.search(bearer, "", "all", null, false, 20, 10);
            assertEquals(6, last.items().size());
            assertNotEquals(first.items().get(0).id(), last.items().get(0).id());

            assertEquals(5, members.search(bearer, "", "disabled", null, false, 0, 100).total());
            assertEquals(21, members.search(bearer, "", "active", null, false, 0, 100).total());
            assertEquals(10, members.search(bearer, "", "all", List.of(sales), false, 0, 100).total());
            assertEquals(15, members.search(bearer, "", "all", List.of(sales, east), false, 0, 100).total());
            assertEquals(0, members.search(bearer, "", "all", List.of(), false, 0, 100).total());
            assertEquals(11, members.search(bearer, "", "all", null, true, 0, 100).total(), "10 without department plus the owner without a profile");

            assertEquals(1, members.search(bearer, "ZHANG_", "all", null, false, 0, 100).total(), "username, case-insensitive");
            assertEquals(1, members.search(bearer, "e13", "all", null, false, 0, 100).total(), "employee number");
            assertEquals(0, members.search(bearer, "%", "all", null, false, 0, 100).total(), "wildcards are literal");
            assertEquals(1, members.search(bearer, "成员03", "all", null, false, 0, 100).total());

            var summary = members.summary(bearer);
            assertEquals(26, summary.total());
            assertEquals(21, summary.active());
            assertEquals(1, summary.admins());

            assertEquals(400, assertThrows(ResponseStatusException.class, () -> members.search(bearer, "", "all", null, false, 0, 101)).getStatusCode().value());
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> members.search(bearer, "", "deleted", null, false, 0, 10)).getStatusCode().value());
        }

        @Test
        void auditPagesNewestFirstWithActorNames() {
            String bearer = "Bearer " + auth.login(admin(), "PagingPassword123!").token();
            var actor = auth.actor(bearer);
            for (int i = 0; i < 7; i++) audit.record(actor, "department.created", "target-" + i);
            var page = members.auditList(bearer, 0, 5);
            assertEquals(5, page.size());
            assertEquals("管理员", page.get(0).actorName());
            assertFalse(page.get(0).occurredAt().isBefore(page.get(4).occurredAt()));
            assertTrue(members.auditList(bearer, 5, 5).size() >= 2);
            assertEquals(400, assertThrows(ResponseStatusException.class, () -> members.auditList(bearer, -1, 5)).getStatusCode().value());
        }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:sqlite:file:paging-test?mode=memory&cache=shared",
            "workdsh.bootstrap.admin-email=paging-sqlite@example.test",
            "workdsh.bootstrap.admin-password=PagingPassword123!",
            "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
    @ActiveProfiles("enterprise-sqlite")
    class OnSqlite extends Scenario {
        String admin() { return "paging-sqlite@example.test"; }
    }

    @Nested
    @SpringBootTest(properties = {
            "spring.datasource.url=jdbc:h2:mem:paging-h2;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
            "workdsh.bootstrap.admin-email=paging-h2@example.test",
            "workdsh.bootstrap.admin-password=PagingPassword123!",
            "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
    class OnH2 extends Scenario {
        String admin() { return "paging-h2@example.test"; }
    }
}
