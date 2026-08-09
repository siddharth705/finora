package com.finora.imports.pdf.acquisition;

/**
 * An acquirer that infers characters from pixels rather than reading them.
 *
 * <h2>Why the distinction is a type</h2>
 *
 * Routing needs to know which acquirers are last resorts, and "is this one a recogniser" is not a
 * question {@link DocumentTextAcquirer} can answer about itself without inviting every future
 * implementation to answer it differently. A separate type states it once, and lets Spring hand
 * routing exactly the set it needs -- empty when no recogniser is deployed, which is the default and
 * must stay a supported configuration rather than a broken one.
 *
 * <p>Nothing else distinguishes them. A recogniser produces the same {@link AcquiredDocument} from
 * the same bytes and is judged by the same parser and the same validators; the marker exists for
 * ordering, not for privilege. In particular it grants no authority to declare a figure correct --
 * see {@link DocumentTextAcquirer}.
 */
public interface RecognisingTextAcquirer extends DocumentTextAcquirer {
}
