package com.finora.transactions;

import com.finora.entity.Transaction;
import com.finora.util.CounterpartyType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The counterparty's trip onto the wire. Small, but it pins the two things a client depends on and
 * cannot check for itself.
 */
class TransactionDtoCounterpartyTest {

    @Test
    void theTypeReachesTheWire() {
        Transaction t = transaction("UPI-SUNIL VERMA-sampleuser@ybl-REF91", Transaction.Type.EXPENSE);

        assertThat(TransactionDto.from(t, "Other").counterpartyType()).isEqualTo("PERSON");
    }

    @Test
    void theSameCounterpartyTypeIsSentRegardlessOfDirection() {
        // The property the whole layer rests on. Direction is `type`, and only `type`. V123 encoded
        // direction into a category name ("Paid a Person") and 99 of the 434 rows it labelled were
        // money RECEIVED -- this asserts the counterparty field cannot repeat that, by showing the
        // identical narration yields the identical type whichever way the money moved.
        String narration = "UPI-SUNIL VERMA-sampleuser@ybl-REF92";

        TransactionDto sent = TransactionDto.from(transaction(narration, Transaction.Type.EXPENSE), "Other");
        TransactionDto received = TransactionDto.from(transaction(narration, Transaction.Type.INCOME), "Other");

        assertThat(sent.counterpartyType()).isEqualTo(received.counterpartyType()).isEqualTo("PERSON");
        assertThat(sent.type()).isEqualTo("EXPENSE");
        assertThat(received.type()).isEqualTo("INCOME");
    }

    @Test
    void anUntypedTransactionSerialisesAsUnknownRatherThanNull() {
        // A row that predates the backfill has never been through the classifier at all. The entity
        // field is initialised to UNKNOWN so a client never receives null and never needs a
        // null-guard for a field that is NOT NULL in the database (V142).
        Transaction t = new Transaction();
        t.setTxnDate(LocalDate.now());
        t.setAmount(BigDecimal.TEN);
        t.setTxnType(Transaction.Type.EXPENSE);
        t.setTags(List.of());

        assertThat(t.getCounterpartyClassifierVersion()).isNull();   // never classified
        assertThat(TransactionDto.from(t, "Other").counterpartyType()).isEqualTo("UNKNOWN");
    }

    @Test
    void theGroupingKeyIsNotExposed() {
        // Deliberate. A "name:" key is a guess derived from narration text, and anything on the wire
        // will eventually be rendered; a client showing it would be presenting a guess as a resolved
        // identity. Asserted by reflection over the record's components so that ADDING the field
        // later fails here and forces that decision to be made on purpose.
        assertThat(TransactionDto.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .contains("counterpartyType")
                .doesNotContain("counterpartyKey", "counterpartyClassifierVersion");
    }

    private static Transaction transaction(String description, Transaction.Type type) {
        Transaction t = new Transaction();
        t.setTxnDate(LocalDate.now());
        t.setDescription(description);
        t.setAmount(BigDecimal.valueOf(486));
        t.setTxnType(type);
        t.setTags(List.of());
        t.applyCounterpartyTyping(description);
        assertThat(t.getCounterpartyType()).isNotEqualTo(CounterpartyType.UNKNOWN);
        return t;
    }
}
