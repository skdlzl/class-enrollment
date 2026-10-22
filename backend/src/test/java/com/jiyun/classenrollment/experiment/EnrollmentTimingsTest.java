package com.jiyun.classenrollment.experiment;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class EnrollmentTimingsTest {
    Map<?,?> phases(EnrollmentTimings t){return (Map<?,?>)t.snapshot().get("phases");}
    @Test void failureRecordedAndProxyThreadStateCleared() {
        var t=new EnrollmentTimings(true);
        assertThrows(IllegalStateException.class,()->t.proxy(()->{
            try(var b=t.body()){return t.measure("validationQuery",()->{throw new IllegalStateException("test");});}
        }));
        assertEquals(1L,((Map<?,?>)phases(t).get("validationQuery")).get("failures"));
        try(var b=t.body()){}
        assertEquals(1L,((Map<?,?>)phases(t).get("transactionEntry")).get("count"));
        assertFalse(phases(t).containsKey("completionTail.committed"));
    }
    @Test void recordsTransactionCompletionSeparatelyFromMethodBody() {
        for(int status:new int[]{TransactionSynchronization.STATUS_COMMITTED,TransactionSynchronization.STATUS_ROLLED_BACK}) {
            var t=new EnrollmentTimings(true);
            TransactionSynchronizationManager.initSynchronization();
            try {
                t.proxy(()->{
                    try(var b=t.body()){t.measure("saveCall",()->1);}
                    var hooks=TransactionSynchronizationManager.getSynchronizations();
                    if(status==TransactionSynchronization.STATUS_COMMITTED)hooks.forEach(h->h.beforeCommit(false));
                    hooks.forEach(h->h.afterCompletion(status));return 1;
                });
                String result=status==TransactionSynchronization.STATUS_COMMITTED?"committed":"rolledBack";
                assertTrue(phases(t).containsKey("completionTail."+result));
                assertEquals(status==TransactionSynchronization.STATUS_COMMITTED,phases(t).containsKey("commitCallbacks.committed"));
            } finally {TransactionSynchronizationManager.clearSynchronization();}
        }
    }
    @Test void disabledInstrumentationDoesNotCollectOrChangeResults() {
        var t=new EnrollmentTimings(false);
        assertEquals(7,t.proxy(()->{try(var b=t.body()){return t.measure("saveCall",()->7);}}));
        assertTrue(phases(t).isEmpty());
    }
}
