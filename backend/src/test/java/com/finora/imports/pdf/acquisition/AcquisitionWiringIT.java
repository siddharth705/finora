package com.finora.imports.pdf.acquisition;

import com.finora.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * That the application actually wires routing, in the configuration it ships in.
 *
 * <h2>Why this exists</h2>
 *
 * Adding the acquirer constructor to {@code PdfPreviewGenerator} left it with two constructors, and
 * Spring will not choose between them. Every one of the 344 context-dependent tests failed at once,
 * reporting "No default constructor found" against PdfPreviewGenerator -- accurate, and pointing at
 * the class rather than at the ambiguity that caused it. The unit tests for routing all passed
 * throughout, because they construct it directly.
 *
 * <p>So the wiring gets its own assertion. A seam that is correct but unreachable is worth nothing,
 * and the way it becomes unreachable is a change nowhere near it.
 *
 * <p><b>Bug fix.</b> Originally a bare {@code @SpringBootTest} with no datasource of its own, which
 * meant it inherited the "dev" profile's real {@code localhost:5432} datasource instead of the
 * Testcontainers Postgres every other {@code *IT} class gets from {@link AbstractIntegrationTest} --
 * failing with {@code Connection to localhost:5432 refused} anywhere that port has nothing
 * listening, including this project's own CI runner. Extending the shared base class is what every
 * other integration test in this codebase already does for exactly this reason.
 */
class AcquisitionWiringIT extends AbstractIntegrationTest {

    @Autowired
    private DocumentTextAcquirer acquirer;

    @Autowired
    private List<DocumentTextAcquirer> everyAcquirer;

    /** Injecting the interface must reach routing, not native extraction directly. */
    @Test
    void theInjectedAcquirerIsTheRoutingOne() {
        assertThat(acquirer).isInstanceOf(RoutingTextAcquirer.class);
    }

    /**
     * And no recogniser is deployed, which is the shipped configuration.
     *
     * <p>Asserted rather than assumed: an OCR engine is an operational dependency, and a recogniser
     * appearing in the context by accident would start routing documents to something the
     * deployment may not have installed. If one is ever added deliberately, this test should be the
     * thing that says so out loud.
     */
    @Test
    void noRecogniserShipsByDefault() {
        assertThat(everyAcquirer)
                .as("native and routing, and nothing that infers characters from pixels")
                .hasOnlyElementsOfTypes(NativePdfAcquirer.class, RoutingTextAcquirer.class);
    }
}
