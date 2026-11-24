package com.ecofood.app.config;

import com.ecofood.core.enums.AuditAction;
import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audited {
  AuditAction value();
}
