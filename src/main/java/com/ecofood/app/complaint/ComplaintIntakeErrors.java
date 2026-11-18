package com.ecofood.app.complaint;

import com.ecofood.app.config.*;
import java.util.Map;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.core.annotation.Order;

/** Never echo payload, request URL, receipt capability or persistence/provider messages. */
@RestControllerAdvice(assignableTypes = {ComplaintIntakeController.class, ComplaintProcessingController.class}) @Order(-10)
public class ComplaintIntakeErrors {
  @ExceptionHandler({BadRequestException.class, MethodArgumentNotValidException.class, HttpMessageNotReadableException.class,
      MethodArgumentTypeMismatchException.class, org.springframework.web.bind.MissingServletRequestParameterException.class})
  public ResponseEntity<Map<String,String>> invalid(Exception ex) { return error(400, "Invalid Complaint request; check fields and remove credentials"); }
  @ExceptionHandler(ForbiddenException.class) public ResponseEntity<Map<String,String>> forbidden() { return error(403, "Complaint access denied; sign in again if permissions changed"); }
  @ExceptionHandler(IllegalArgumentException.class) public ResponseEntity<Map<String,String>> missing() { return error(404, "Complaint or Invoice unavailable"); }
  @ExceptionHandler({IllegalStateException.class, OptimisticLockingFailureException.class}) public ResponseEntity<Map<String,String>> conflict() { return error(409, "Complaint request conflicts with an existing request or pending operation"); }
  @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,String>> status(ResponseStatusException ex) {
    int code = ex.getStatusCode().value();
    return error(code, code == 404 ? "Complaint or receipt access unavailable" : code == 401 ? "Unauthorized" : code == 429 ? "Too many Complaint requests; retry later" : "Complaint service or routing is unavailable");
  }
  @ExceptionHandler(Exception.class) public ResponseEntity<Map<String,String>> unavailable() { return error(503, "Complaint service is temporarily unavailable"); }
  private ResponseEntity<Map<String,String>> error(int code, String message) { return ResponseEntity.status(code).cacheControl(CacheControl.noStore()).header("Referrer-Policy", "no-referrer").body(Map.of("message", message)); }
}
