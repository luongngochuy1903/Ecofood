package com.ecofood.app.userACL.controller;

import com.ecofood.app.userACL.dto.RoleResponse;
import com.ecofood.app.userACL.service.RoleService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

  private final RoleService roleService;

  /**
   * Backs the Access & Permissions role picker — ?actor=ENTERPRISE returns
   * only ENTERPRISE_* roles, so on for ECO/MASTER/TENANT (see
   * RoleService.listByActor).
   */
  @GetMapping
  public ResponseEntity<List<RoleResponse>> listByActor(@RequestParam String actor) {
    return ResponseEntity.ok(roleService.listByActor(actor));
  }
}
