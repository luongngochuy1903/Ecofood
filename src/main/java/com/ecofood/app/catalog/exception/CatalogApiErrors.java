package com.ecofood.app.catalog.exception;

import com.ecofood.app.catalog.controller.CatalogController;
import com.ecofood.app.catalog.controller.CatalogExternalController;
import java.util.Map;
import org.springframework.core.*;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.*;
import org.springframework.web.servlet.config.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes={CatalogController.class,CatalogExternalController.class})
public class CatalogApiErrors implements WebMvcConfigurer {
  @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String,String>> malformed(Exception ignored) { return ResponseEntity.badRequest().body(Map.of("message","Invalid Catalog publication request")); }
  @Override public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest req,jakarta.servlet.http.HttpServletResponse res,Object handler) { res.setHeader("Cache-Control","no-store"); return true; }
    }).addPathPatterns("/api/v1/catalog-publications/**","/api/external/v1/catalog/**");
  }
}
