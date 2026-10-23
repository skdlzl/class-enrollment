package com.jiyun.dbsplit;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class MetricsTest {
 @Test void disabledInstrumentationKeepsBehavior(){var m=new Metrics(false);assertEquals(7,m.time("work",()->7));assertThrows(IllegalStateException.class,()->m.time("failure",()->{throw new IllegalStateException();}));assertTrue(((Map<?,?>)m.snapshot().get("phases")).isEmpty());}
 @Test void countsFailedOperations(){var m=new Metrics(true);assertThrows(IllegalStateException.class,()->m.time("work",()->{throw new IllegalStateException();}));var p=(Map<?,?>)((Map<?,?>)m.snapshot().get("phases")).get("work");assertEquals(1L,p.get("count"));assertEquals(1L,p.get("failures"));}
}
