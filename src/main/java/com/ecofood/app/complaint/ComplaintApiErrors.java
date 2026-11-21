package com.ecofood.app.complaint;

import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.*;

@Order(Ordered.HIGHEST_PRECEDENCE)
@RestControllerAdvice(assignableTypes=ComplaintController.class)
public class ComplaintApiErrors implements WebMvcConfigurer {
  @ExceptionHandler({HttpMessageNotReadableException.class,MethodArgumentNotValidException.class,MethodArgumentTypeMismatchException.class})
  public ResponseEntity<Map<String,String>> malformed(Exception ignored) { return ResponseEntity.badRequest().body(Map.of("message","Invalid Complaint payload or parameters")); }
  @Override public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(new HandlerInterceptor() {
      @Override public boolean preHandle(jakarta.servlet.http.HttpServletRequest req,jakarta.servlet.http.HttpServletResponse res,Object handler) {
        res.setHeader("Cache-Control","no-store"); return true;
      }
    }).addPathPatterns("/api/v1/complaints/**","/api/v1/customers/me/complaints/**");
  }
}
