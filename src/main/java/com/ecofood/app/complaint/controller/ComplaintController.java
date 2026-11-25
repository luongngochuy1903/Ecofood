package com.ecofood.app.complaint.controller;

import com.ecofood.app.config.Audited;
import com.ecofood.core.enums.AuditAction;

import static com.ecofood.core.dto.ComplaintDtos.*;

import com.ecofood.core.dto.ComplaintDtos;
import com.ecofood.core.dto.ComplaintView;
import com.ecofood.core.enums.ComplaintStatusEnum;
import com.ecofood.core.enums.ComplaintTypeEnum;
import com.ecofood.core.service.ComplaintService;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class ComplaintController {
  private final ComplaintService service;
  @Audited(AuditAction.CREATE)
  @PostMapping("/customers/me/complaints") @ResponseStatus(HttpStatus.CREATED)
  public ComplaintView create(@Valid @RequestBody Create body) { return service.create(body); }
  @GetMapping("/customers/me/complaints") public Page<? extends ComplaintView> ownList(@RequestParam(required=false) ComplaintStatusEnum status,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.customerList(status,page,size); }
  @GetMapping("/customers/me/complaints/{id}") public ComplaintView ownDetail(@PathVariable String id) { return service.customerDetail(id); }
  @GetMapping("/customers/me/complaints/{id}/events") public List<ComplaintDtos.ComplaintEvent> ownEvents(@PathVariable String id) { return service.customerDetail(id).getEvents(); }
  @Audited(AuditAction.UPDATE)
  @PostMapping("/customers/me/complaints/{id}/replies") public ComplaintView reply(@PathVariable String id,@Valid @RequestBody Reply body) { return service.reply(id,body); }
  @GetMapping("/complaints/capabilities") public Capabilities capabilities() { return service.capabilities(); }
  @GetMapping("/complaints/assignees") public List<Assignee> assignees(@RequestParam ComplaintTypeEnum type) { return service.assignees(type); }
  @GetMapping("/complaints") public Page<? extends ComplaintView> list(@RequestParam(required=false) ComplaintTypeEnum type,@RequestParam(required=false) ComplaintStatusEnum status,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.adminList(type,status,page,size); }
  @GetMapping("/complaints/{id}") public Detail detail(@PathVariable String id) { return service.adminDetail(id); }
  @GetMapping("/complaints/{id}/events") public List<ComplaintDtos.ComplaintEvent> events(@PathVariable String id) { return service.adminDetail(id).complaint().getEvents(); }
  @Audited(AuditAction.UPDATE)
  @PostMapping("/complaints/{id}/{action}") public ComplaintView action(@PathVariable String id,@PathVariable String action,@Valid @RequestBody Action body) { return service.action(id,action,body); }
}
