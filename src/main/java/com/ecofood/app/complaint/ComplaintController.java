package com.ecofood.app.complaint;

import static com.ecofood.app.complaint.ComplaintDtos.*;
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
  @PostMapping("/customers/me/complaints") @ResponseStatus(HttpStatus.CREATED)
  public Complaint create(@Valid @RequestBody Create body) { return service.create(body); }
  @GetMapping("/customers/me/complaints") public Page<Complaint> ownList(@RequestParam(required=false) Complaint.Status status,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.customerList(status,page,size); }
  @GetMapping("/customers/me/complaints/{id}") public Complaint ownDetail(@PathVariable String id) { return service.customerDetail(id); }
  @GetMapping("/customers/me/complaints/{id}/events") public List<Complaint.ComplaintEvent> ownEvents(@PathVariable String id) { return service.customerDetail(id).getEvents(); }
  @PostMapping("/customers/me/complaints/{id}/replies") public Complaint reply(@PathVariable String id,@Valid @RequestBody Reply body) { return service.reply(id,body); }
  @GetMapping("/complaints/capabilities") public Capabilities capabilities() { return service.capabilities(); }
  @GetMapping("/complaints/assignees") public List<Assignee> assignees(@RequestParam Complaint.Type type) { return service.assignees(type); }
  @GetMapping("/complaints") public Page<Complaint> list(@RequestParam(required=false) Complaint.Type type,@RequestParam(required=false) Complaint.Status status,
      @RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int size) { return service.adminList(type,status,page,size); }
  @GetMapping("/complaints/{id}") public Detail detail(@PathVariable String id) { return service.adminDetail(id); }
  @GetMapping("/complaints/{id}/events") public List<Complaint.ComplaintEvent> events(@PathVariable String id) { return service.adminDetail(id).complaint().getEvents(); }
  @PostMapping("/complaints/{id}/{action}") public Complaint action(@PathVariable String id,@PathVariable String action,@Valid @RequestBody Action body) { return service.action(id,action,body); }
}
