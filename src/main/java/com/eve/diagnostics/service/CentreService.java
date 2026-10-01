package com.eve.diagnostics.service;

import com.eve.diagnostics.dto.request.AttachTestToCentreRequest;
import com.eve.diagnostics.dto.request.CreateCentreRequest;
import com.eve.diagnostics.dto.response.CentreDetailsResponse;
import com.eve.diagnostics.dto.response.CentreResponse;
import com.eve.diagnostics.dto.response.CentreTestResponse;
import com.eve.diagnostics.dto.response.PageResponse;
import com.eve.diagnostics.dto.response.TestResponse;
import com.eve.diagnostics.entity.CentreTest;
import com.eve.diagnostics.entity.DiagnosticCentre;
import com.eve.diagnostics.entity.DiagnosticTest;
import com.eve.diagnostics.exception.ConflictException;
import com.eve.diagnostics.exception.ResourceNotFoundException;
import com.eve.diagnostics.repository.CentreTestRepository;
import com.eve.diagnostics.repository.DiagnosticCentreRepository;
import com.eve.diagnostics.repository.DiagnosticTestRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CentreService {

    private static final Logger log = LoggerFactory.getLogger(CentreService.class);

    private final DiagnosticCentreRepository centreRepository;
    private final DiagnosticTestRepository testRepository;
    private final CentreTestRepository centreTestRepository;

    /**
     * Listing centres. When city is supplied the list is narrowed to that city,
     * which uses idx_diagnostic_centres_city.
     */
    @Transactional(readOnly = true)
    public PageResponse<CentreResponse> listCentres(String city, Pageable pageable) {
        Page<DiagnosticCentre> page = (city == null || city.isBlank())
                ? centreRepository.findAll(pageable)
                : centreRepository.findByCityIgnoreCase(city.trim(), pageable);

        return PageResponse.from(page.map(CentreResponse::from));
    }

    /**
     * One centre with all of its tests. Two queries rather than one, which keeps
     * this a fixed cost no matter how many tests the centre offers.
     */
    @Transactional(readOnly = true)
    public CentreDetailsResponse getCentre(UUID centreId) {
        DiagnosticCentre centre = findCentreOrThrow(centreId);
        List<CentreTest> centreTests = centreTestRepository.findByCentreId(centreId);
        return CentreDetailsResponse.from(centre, centreTests);
    }

    @Transactional(readOnly = true)
    public PageResponse<CentreTestResponse> listTestsForCentre(UUID centreId, Pageable pageable) {
        // Check the centre exists first, otherwise an unknown id would return an
        // empty page that looks like "this centre has no tests".
        findCentreOrThrow(centreId);

        Page<CentreTest> page = centreTestRepository.findByCentreId(centreId, pageable);
        return PageResponse.from(page.map(CentreTestResponse::from));
    }

    @Transactional(readOnly = true)
    public PageResponse<TestResponse> listTests(String name, Pageable pageable) {
        Page<DiagnosticTest> page = (name == null || name.isBlank())
                ? testRepository.findAll(pageable)
                : testRepository.findByNameContainingIgnoreCase(name.trim(), pageable);

        return PageResponse.from(page.map(TestResponse::from));
    }

    @Transactional
    public CentreResponse createCentre(CreateCentreRequest request) {
        DiagnosticCentre centre = centreRepository.save(DiagnosticCentre.builder()
                .name(request.name().trim())
                .city(request.city().trim())
                .address(request.address() == null ? null : request.address().trim())
                .build());

        log.info("Created diagnostic centre id={} name={} city={}", centre.getId(), centre.getName(), centre.getCity());
        return CentreResponse.from(centre);
    }

    @Transactional
    public CentreTestResponse attachTestToCentre(UUID centreId, AttachTestToCentreRequest request) {
        DiagnosticCentre centre = findCentreOrThrow(centreId);

        DiagnosticTest test = testRepository.findById(request.testId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Diagnostic test not found with id " + request.testId()));

        if (centreTestRepository.existsByCentreIdAndTestId(centreId, request.testId())) {
            throw new ConflictException("This centre already offers that test.");
        }

        CentreTest centreTest = centreTestRepository.save(CentreTest.builder()
                .centre(centre)
                .test(test)
                .price(request.price())
                .build());

        log.info("Attached test={} to centre={} at price={}", test.getId(), centreId, centreTest.getPrice());
        return CentreTestResponse.from(centreTest);
    }

    private DiagnosticCentre findCentreOrThrow(UUID centreId) {
        return centreRepository.findById(centreId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Diagnostic centre not found with id " + centreId));
    }
}