package com.finora.imports.pdf.acquisition;

import com.finora.AbstractIntegrationTest;
import com.finora.imports.pdf.ocr.TesseractRecogniser;
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

    @Autowired
    private List<RecognisingTextAcquirer> recognisers;

    /** Injecting the interface must reach routing, not native extraction directly. */
    @Test
    void theInjectedAcquirerIsTheRoutingOne() {
        assertThat(acquirer).isInstanceOf(RoutingTextAcquirer.class);
    }

    /**
     * Tesseract is the deployed recogniser -- the state this test asserted AGAINST until the OCR
     * deployment decision was made (see {@code docs/engineering/import/ocr-engine-evaluation.md},
     * "OCR-5"). This is the test that comment said should say so out loud when that day came.
     *
     * <p>Asserted rather than assumed: a recogniser appearing in the context by accident would
     * start routing documents to something the deployment may not actually have installed, and one
     * silently missing would leave scanned statements failing without anyone having decided that.
     * {@link TesseractRecogniser} itself still degrades safely when the binary is absent --
     * {@code supports()} reports that rather than assuming it -- so this asserts intent (the bean
     * exists), not capability (the binary works), which is what {@code ScannedDocumentRoutingTest}
     * and a build-time {@code tesseract --version} check are for.
     */
    @Test
    void theDeployedRecogniserIsTesseract() {
        assertThat(everyAcquirer)
                .as("native, routing, and the one deployed recogniser")
                .hasOnlyElementsOfTypes(NativePdfAcquirer.class, RoutingTextAcquirer.class,
                        TesseractRecogniser.class);
        assertThat(recognisers)
                .as("the exact list RoutingTextAcquirer receives")
                .hasOnlyElementsOfTypes(TesseractRecogniser.class)
                .hasSize(1);
    }
}
