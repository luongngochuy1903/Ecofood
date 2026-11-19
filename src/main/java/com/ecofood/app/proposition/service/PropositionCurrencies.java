package com.ecofood.app.proposition.service;

import com.ecofood.app.proposition.entity.PropositionCurrencySettings;
import com.ecofood.app.proposition.repository.PropositionCurrencySettingsRepository;
import com.ecofood.core.dto.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.bson.types.ObjectId;
import org.springframework.dao.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PropositionCurrencies {
    private final PropositionCurrencySettingsRepository repository;

    public List<String> supportedCurrencies() {
        return Currency.getAvailableCurrencies().stream()
                .filter(currency -> currency.getDefaultFractionDigits() >= 0
                        && currency.getDefaultFractionDigits() <= 4)
                .map(Currency::getCurrencyCode)
                .sorted()
                .toList();
    }

    public String resolvePlan(ObjectId enterprise, String value) {
        return normalize(value == null || value.isBlank() ? "USD" : value);
    }

    public PropositionCurrencySettingsResponse settings(ObjectId enterprise) {
        return repository
                .findByEnterpriseId(enterprise)
                .map(
                        s ->
                                new PropositionCurrencySettingsResponse(
                                        s.getRevision(),
                                        supportedCurrencies(),
                                        s.getDefaultCurrency()))
                .orElseGet(
                        () -> new PropositionCurrencySettingsResponse(null, supportedCurrencies(), "USD"));
    }

    public String resolve(ObjectId enterprise, String value) {
        var policy = settings(enterprise);
        return normalize(value == null || value.isBlank() ? policy.defaultCurrency() : value);
    }

    public PropositionCurrencySettingsResponse update(
            ObjectId enterprise, PropositionCurrencySettingsRequest r) {
        var previous = repository.findByEnterpriseId(enterprise);
        if (!Objects.equals(
                previous.map(PropositionCurrencySettings::getRevision).orElse(null),
                r.expectedRevision()))
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Currency settings changed; reload before saving");
        // Retain the legacy API field, but currency settings now set only a default.
        var codes = supportedCurrencies();
        var fallback = normalize(r.defaultCurrency());
        var entity =
                previous.orElseGet(
                        () ->
                                PropositionCurrencySettings.builder()
                                        .enterpriseId(enterprise)
                                        .build());
        entity.setAvailableCurrencies(codes);
        entity.setDefaultCurrency(fallback);
        try {
            repository.save(entity);
        } catch (OptimisticLockingFailureException | DuplicateKeyException ex) {
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Currency settings changed; reload before saving");
        }
        return new PropositionCurrencySettingsResponse(entity.getRevision(), codes, fallback);
    }

    private String normalize(String value) {
        try {
            String code = value.trim().toUpperCase(Locale.ROOT);
            int scale = Currency.getInstance(code).getDefaultFractionDigits();
            if (scale < 0 || scale > 4) throw new IllegalArgumentException();
            return code;
        } catch (RuntimeException ex) {
            throw PropositionAccess.error(
                    HttpStatus.BAD_REQUEST, "A supported ISO currency code is required");
        }
    }
}
