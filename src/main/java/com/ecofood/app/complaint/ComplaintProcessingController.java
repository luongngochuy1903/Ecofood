package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.complaint.ComplaintProcessingContracts.*;
import com.ecofood.app.config.BadRequestException;
import com.ecofood.core.dto.PageResponse;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.*;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/complaints") @RequiredArgsConstructor
public class ComplaintProcessingController {
  private final ComplaintProcessingService service;
  @GetMapping public ResponseEntity<PageResponse<InboxSummary>> list(@RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size,
      @RequestParam(required=false) Kind kind, @RequestParam(required=false) Status status,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant from,
      @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE_TIME) Instant to,
      @RequestParam(defaultValue="createdAt") String sortBy, @RequestParam(defaultValue="DESC") Sort.Direction direction,
      @RequestParam(required=false) String complaintCode, @RequestParam(required=false) String invoiceCode,
      @RequestParam(required=false) String categoryCode, @RequestParam(required=false) String queueId,
      @RequestParam MultiValueMap<String,String> parameters) {
    query(parameters, Set.of("page", "size", "kind", "status", "from", "to", "sortBy", "direction", "complaintCode", "invoiceCode", "categoryCode", "queueId"));
    if (page < 0 || size < 1 || size > 100) throw new BadRequestException("Invalid Complaint page");
    return ok(service.search(page, size, kind, status, from, to, sortBy, direction, new Search(complaintCode, invoiceCode, categoryCode, queueId)));
  }
  @GetMapping("/options") public ResponseEntity<InboxOptions> options() { return ok(service.options()); }
  @GetMapping("/{id}") public ResponseEntity<Detail> detail(@PathVariable String id) { return ok(service.detail(id)); }
  @GetMapping("/{id}/history") public ResponseEntity<PageResponse<HistoryEntry>> history(@PathVariable String id,
      @RequestParam(defaultValue="0") int page, @RequestParam(defaultValue="20") int size, @RequestParam MultiValueMap<String,String> parameters) {
    query(parameters, Set.of("page", "size")); return ok(service.history(id, page, size));
  }
  @PostMapping("/{id}/assign") public ResponseEntity<CommandResult> assign(@PathVariable String id, @Valid @RequestBody Assignment request) { return ok(service.assign(id, request)); }
  @PostMapping("/{id}/start") public ResponseEntity<CommandResult> start(@PathVariable String id, @Valid @RequestBody Action request) { return ok(service.action(id, request, Operation.START_PROCESSING)); }
  @PostMapping("/{id}/request-information") public ResponseEntity<CommandResult> information(@PathVariable String id, @Valid @RequestBody Action request) { return ok(service.action(id, request, Operation.REQUEST_INFORMATION)); }
  @PostMapping("/{id}/resume") public ResponseEntity<CommandResult> resume(@PathVariable String id, @Valid @RequestBody Action request) { return ok(service.action(id, request, Operation.RESUME_PROCESSING)); }
  @PostMapping("/{id}/resolve") public ResponseEntity<CommandResult> resolve(@PathVariable String id, @Valid @RequestBody Action request) { return ok(service.action(id, request, Operation.RESOLVE)); }
  @PostMapping("/{id}/escalate") public ResponseEntity<CommandResult> escalate(@PathVariable String id, @Valid @RequestBody Action request) { return ok(service.action(id, request, Operation.ESCALATE)); }
  private void query(MultiValueMap<String,String> parameters, Set<String> allowed) {
    if (!allowed.containsAll(parameters.keySet()) || parameters.values().stream().anyMatch(values -> values.size() != 1)) throw new BadRequestException("Unsupported query scope");
  }
  private <T> ResponseEntity<T> ok(T value) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(value); }
}
