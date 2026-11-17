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

    public PropositionCurrencySettingsResponse settings(ObjectId enterprise) {
        return repository
                .findByEnterpriseId(enterprise)
                .map(
                        s ->
                                new PropositionCurrencySettingsResponse(
                                        s.getRevision(),
                                        s.getAvailableCurrencies(),
                                        s.getDefaultCurrency()))
                .orElseGet(
                        () -> new PropositionCurrencySettingsResponse(null, List.of("USD"), "USD"));
    }

    public String resolve(ObjectId enterprise, String value) {
        var policy = settings(enterprise);
        var code = value == null || value.isBlank() ? policy.defaultCurrency() : normalize(value);
        if (!policy.availableCurrencies().contains(code))
            throw PropositionAccess.error(
                    HttpStatus.BAD_REQUEST, "Currency is not enabled for this Enterprise: " + code);
        return code;
    }

    public PropositionCurrencySettingsResponse update(
            ObjectId enterprise, PropositionCurrencySettingsRequest r) {
        var previous = repository.findByEnterpriseId(enterprise);
        if (!Objects.equals(
                previous.map(PropositionCurrencySettings::getRevision).orElse(null),
                r.expectedRevision()))
            throw PropositionAccess.error(
                    HttpStatus.CONFLICT, "Currency settings changed; reload before saving");
        var codes = r.availableCurrencies().stream().map(this::normalize).distinct().toList();
        var fallback = normalize(r.defaultCurrency());
        if (!codes.contains(fallback))
            throw PropositionAccess.error(
                    HttpStatus.BAD_REQUEST, "Default currency must be enabled");
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
