package com.jiyun.classenrollment.course.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jiyun.classenrollment.course.api.CourseQueryController;
import org.junit.jupiter.api.Test;
import org.redisson.api.*;
import org.redisson.client.codec.StringCodec;
import org.redisson.misc.CompletableFutureWrapper;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CourseSearchCacheTest {
    final RedissonClient redis=mock(RedissonClient.class);
    @SuppressWarnings("unchecked") final RBucket<String> bucket=mock(RBucket.class);
    final ObjectMapper mapper=new ObjectMapper();
    final AtomicReference<String> payload=new AtomicReference<>();
    final CourseQueryController.Page page=new CourseQueryController.Page(List.of(
        new CourseQueryController.Item(1,"A","과목","P","CS",3,100,0,1)),false,null);
    @SuppressWarnings("unchecked") final Supplier<CourseQueryController.Page> loader=mock(Supplier.class);
    CourseSearchCache setup(boolean enabled) {
        when(redis.<String>getBucket(anyString(),eq(StringCodec.INSTANCE))).thenReturn(bucket);
        when(bucket.getAsync()).thenAnswer(i->new CompletableFutureWrapper<>(CompletableFuture.completedFuture(payload.get())));
        when(bucket.setAsync(anyString(),any(Duration.class))).thenAnswer(i->{payload.set(i.getArgument(0));return new CompletableFutureWrapper<Void>(CompletableFuture.completedFuture(null));});
        when(loader.get()).thenReturn(page);
        return new CourseSearchCache(redis,mapper,enabled,1000);
    }
    @Test void secondServerUsesSharedPayloadWithoutLoadingDatabase() {
        var first=setup(true);assertEquals(page,first.get("same",loader));
        var second=new CourseSearchCache(redis,mapper,true,1000);
        assertEquals(page,second.get("same",loader));verify(loader,times(1)).get();
        assertEquals(1L,second.snapshot().get("hits"));
    }
    @Test void expiredPayloadReloadsEvenIfDelayedSetSurvivesInRedis() throws Exception {
        var cache=setup(true);
        payload.set(mapper.writeValueAsString(new CourseSearchCache.Entry(System.currentTimeMillis()-1,page)));
        assertEquals(page,cache.get("same",loader));verify(loader).get();
    }
    @Test void redisOutageFallsBackAndSubsequentCallsBypassRedis() {
        var cache=setup(true);
        when(bucket.getAsync()).thenReturn(new CompletableFutureWrapper<>(CompletableFuture.failedFuture(new IllegalStateException("offline"))));
        assertEquals(page,cache.get("same",loader));assertEquals(page,cache.get("same",loader));
        verify(loader,times(2)).get();verify(bucket,times(1)).getAsync();
    }
    @Test void stalledRedisHasBoundedWaitAndDbResultStillReturns() {
        var cache=setup(true);when(bucket.getAsync()).thenReturn(new CompletableFutureWrapper<>(new CompletableFuture<String>()));
        assertTimeoutPreemptively(Duration.ofSeconds(2),()->assertEquals(page,cache.get("same",loader)));
        assertEquals(1L,cache.snapshot().get("errors"));
    }
    @Test void failedQueryIsNeverCached() {
        var cache=setup(true);when(loader.get()).thenThrow(new IllegalStateException("DB failed"));
        assertThrows(IllegalStateException.class,()->cache.get("same",loader));
        verify(bucket,never()).setAsync(anyString(),any(Duration.class));
    }
    @Test void disabledNeverTouchesRedis() {
        var cache=setup(false);clearInvocations(redis,bucket);
        assertEquals(page,cache.get("same",loader));verifyNoInteractions(redis,bucket);
    }
    @Test void malformedPayloadFailsOpen() {
        var cache=setup(true);payload.set("not-json");
        assertEquals(page,cache.get("same",loader));assertEquals(1L,cache.snapshot().get("errors"));
    }
    @Test void failedCacheWriteDoesNotDiscardSuccessfulRead() {
        var cache=setup(true);
        when(bucket.setAsync(anyString(),any(Duration.class))).thenReturn(new CompletableFutureWrapper<>(CompletableFuture.failedFuture(new IllegalStateException("offline"))));
        assertEquals(page,cache.get("same",loader));assertEquals(1L,cache.snapshot().get("errors"));
    }
}
