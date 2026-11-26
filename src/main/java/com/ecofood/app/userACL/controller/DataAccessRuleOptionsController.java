package com.ecofood.app.userACL.controller;
import com.ecofood.app.userACL.service.DataAccessRuleOptionsService;
import com.ecofood.core.dto.DashboardDtos.Option;
import com.ecofood.core.enums.AccessEntityTypeEnum;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor
@RequestMapping("/api/v1/data-access-rules/entities")
public class DataAccessRuleOptionsController {
  private final DataAccessRuleOptionsService service;
  @GetMapping public List<Option> entities(@RequestParam String tenantId, @RequestParam AccessEntityTypeEnum type) {
    return service.entities(tenantId, type);
  }
}
