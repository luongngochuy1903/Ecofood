package com.ecofood.app.customer.controller;

import com.ecofood.core.dto.CustomerDtos;
import com.ecofood.core.service.CustomerQueryService;
import com.ecofood.core.service.CustomerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/customers/me")
@RequiredArgsConstructor
public class CustomerController {
  private final CustomerService service;
  private final CustomerQueryService queries;
  @GetMapping public ResponseEntity<CustomerDtos.Profile> profile() { return noStore(service.profile()); }
  @PutMapping public ResponseEntity<CustomerDtos.Profile> update(@Valid @RequestBody CustomerDtos.UpdateProfile input) { return noStore(service.update(input)); }
  @GetMapping("/invoices") public ResponseEntity<CustomerDtos.Page<CustomerDtos.OwnedInvoice>> invoices(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) { return noStore(queries.invoices(page, size)); }
  @GetMapping("/invoices/{id}") public ResponseEntity<CustomerDtos.OwnedInvoice> invoice(@PathVariable String id) { return noStore(queries.detail(id)); }
  static <T> ResponseEntity<T> noStore(T body) { return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(body); }
}
