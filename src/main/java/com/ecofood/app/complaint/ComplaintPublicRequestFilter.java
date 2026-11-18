package com.ecofood.app.complaint;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component @Order(-120) @RequiredArgsConstructor
public class ComplaintPublicRequestFilter extends OncePerRequestFilter {
  private final ComplaintPublicRateLimiter rates;
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI().substring(request.getContextPath().length());
    return !"POST".equals(request.getMethod()) || !(path.equals("/api/v1/public/invoice-complaints") || path.equals("/api/v1/public/invoice-complaints/options")
        || path.equals("/api/v1/public/complaints/track") || path.matches("/api/v1/public/receipts/[^/]+/complaints"));
  }
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
    try {
      rates.ip(request.getRemoteAddr(), request.getRequestURI().endsWith("/track"));
      if (request.getContentLengthLong() > 65536) { respond(response, 413); return; }
      var bounded = new HttpServletRequestWrapper(request) {
        public ServletInputStream getInputStream() throws IOException {
          var delegate = super.getInputStream();
          return new ServletInputStream() {
            int count;
            public int read() throws IOException { int value = delegate.read(); if (value != -1 && ++count > 65536) throw new IOException("Complaint request too large"); return value; }
            public boolean isFinished() { return delegate.isFinished(); }
            public boolean isReady() { return delegate.isReady(); }
            public void setReadListener(ReadListener listener) { delegate.setReadListener(listener); }
          };
        }
      };
      chain.doFilter(bounded, response);
    } catch (ResponseStatusException ex) { if (!response.isCommitted()) respond(response, ex.getStatusCode().value()); }
    catch (RuntimeException ex) { if (!response.isCommitted()) respond(response, 503); }
  }
  private void respond(HttpServletResponse response, int status) throws IOException {
    response.setStatus(status); response.setContentType("application/json;charset=UTF-8"); response.setHeader("Cache-Control", "no-store"); response.setHeader("Referrer-Policy", "no-referrer");
    if (status == 429) response.setHeader("Retry-After", "60");
    response.getWriter().write("{\"message\":\"" + (status == 429 ? "Too many Complaint requests; retry later" : status == 413 ? "Complaint request too large" : "Complaint service unavailable") + "\"}");
  }
}
