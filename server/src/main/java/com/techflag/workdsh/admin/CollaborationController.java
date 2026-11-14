package com.techflag.workdsh.admin;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import com.techflag.workdsh.admin.CollaborationService.Colleague;
import com.techflag.workdsh.admin.CollaborationService.Completion;
import com.techflag.workdsh.admin.CollaborationService.Handoff;
import com.techflag.workdsh.admin.CollaborationService.MessageEntry;
import com.techflag.workdsh.admin.CollaborationService.NewHandoff;
import com.techflag.workdsh.admin.CollaborationService.NewMessage;
import com.techflag.workdsh.admin.CollaborationService.NotificationEntry;

/** Member collaboration API reached through the Desktop enterprise bridge. */
@RestController
@RequestMapping("/api/collaboration")
public class CollaborationController {
    private final AuthService auth;
    private final CollaborationService collaboration;

    public CollaborationController(AuthService auth, CollaborationService collaboration) {
        this.auth = auth;
        this.collaboration = collaboration;
    }

    public record Contract(int contractVersion) {}

    private AuthService.Actor ready(String authorization) {
        var actor = auth.actor(authorization);
        auth.requireReady(actor);
        return actor;
    }

    @GetMapping("/notifications")
    public List<NotificationEntry> notifications(@RequestHeader(value="Authorization", required=false) String authorization) {
        return collaboration.notifications(ready(authorization));
    }

    @PostMapping("/notifications/{id}/read")
    public java.util.Map<String,Boolean> readNotification(@RequestHeader(value="Authorization", required=false) String authorization,
                                                        @PathVariable String id) {
        collaboration.readNotification(ready(authorization), id);
        return java.util.Map.of("read",true);
    }

    @GetMapping("/handoffs/{id}")
    public Handoff detail(@RequestHeader(value="Authorization", required=false) String authorization,@PathVariable String id) {
        return collaboration.detail(ready(authorization), id);
    }

    @GetMapping("/contract")
    public Contract contract(@RequestHeader(value="Authorization", required=false) String authorization) {
        ready(authorization);
        return new Contract(1);
    }

    @GetMapping("/colleagues")
    public List<Colleague> colleagues(@RequestHeader(value="Authorization", required=false) String authorization) {
        return collaboration.colleagues(ready(authorization));
    }

    @GetMapping("/inbox")
    public List<Handoff> inbox(@RequestHeader(value="Authorization", required=false) String authorization) {
        return collaboration.inbox(ready(authorization));
    }

    @GetMapping("/sent")
    public List<Handoff> sent(@RequestHeader(value="Authorization", required=false) String authorization) {
        return collaboration.sent(ready(authorization));
    }

    @PostMapping("/handoffs")
    @ResponseStatus(HttpStatus.CREATED)
    public Handoff send(@RequestHeader(value="Authorization", required=false) String authorization,
                        @Valid @RequestBody NewHandoff input) {
        return collaboration.send(ready(authorization), input);
    }

    @PostMapping("/handoffs/{id}/complete")
    public Handoff complete(@RequestHeader(value="Authorization", required=false) String authorization,
                            @PathVariable String id, @Valid @RequestBody Completion input) {
        return collaboration.complete(ready(authorization), id, input);
    }

    @GetMapping("/handoffs/{id}/messages")
    public List<MessageEntry> messages(@RequestHeader(value="Authorization", required=false) String authorization,
                                       @PathVariable String id) {
        return collaboration.messages(ready(authorization), id);
    }

    @PostMapping("/handoffs/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public MessageEntry reply(@RequestHeader(value="Authorization", required=false) String authorization,
                              @PathVariable String id, @Valid @RequestBody NewMessage input) {
        return collaboration.reply(ready(authorization), id, input);
    }
}
