package com.ecofood.app.complaint;

import com.ecofood.app.complaint.ComplaintContracts.*;
import com.ecofood.app.complaint.ComplaintCoreContracts.*;
import com.ecofood.app.config.ForbiddenException;
import com.ecofood.core.dto.PageResponse;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

@Service @RequiredArgsConstructor
public class ComplaintHistoryService {
  private final ComplaintRepository complaints;
  private final ComplaintEventRepository events;
  private final ComplaintAuthorization authorization;
  public PageResponse<PublicHistoryEvent> reporter(String id, Actor actor, VerifiedGuestAccess guest, int page, int size) {
    var row = load(id); var access = authorization.requirePolicy();
    if (!access.isReporter(row.asCase(), actor, guest)) throw new ForbiddenException("Reporter history access required");
    var result = history(row, true, page, size);
    return response(result.getContent().stream().map(ComplaintEvent::publicView).toList(), page, size, result.getTotalElements());
  }
  public PageResponse<ComplaintEvent> processing(String id, Actor actor, int page, int size) {
    var row = load(id); authorization.requirePolicy().requireQueueRead(row.asCase(), actor);
    var result = history(row, false, page, size); return response(result.getContent(), page, size, result.getTotalElements());
  }
  /** Snapshot cutoff + exclusion prevent duplicates even if projection completes during this read. */
  private Page<ComplaintEvent> history(Complaint row, boolean reporterOnly, int page, int size) {
    ComplaintValidation.require(page >= 0 && size >= 1 && size <= 100, "Invalid history page");
    var pending = row.getPendingCommit() == null ? null : row.getPendingCommit().event();
    var projected = events.query(row.getIdAsString(), row.getVersion() + 1, pending == null ? null : pending.sequence(), reporterOnly, PageRequest.of(page, size));
    boolean include = pending != null && (!reporterOnly || pending.visibility() == Visibility.PUBLIC);
    long count = projected.getTotalElements(); var content = new ArrayList<>(projected.getContent());
    long offset = (long) page * size;
    if (include && offset <= count && offset + size > count) content.add(pending);
    return new PageImpl<>(content, PageRequest.of(page, size), count + (include ? 1 : 0));
  }
  private Complaint load(String id) {
    return complaints.findCommitted(id).filter(row -> !Boolean.TRUE.equals(row.getDeleted())).orElseThrow(() -> new IllegalArgumentException("Complaint not found"));
  }
  private <T> PageResponse<T> response(List<T> rows, int page, int size, long total) {
    return PageResponse.<T>builder().content(rows).page(page).size(size).totalElements(total).totalPages((int) ((total + size - 1) / size)).build();
  }
}
