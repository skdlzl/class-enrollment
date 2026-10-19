package com.jiyun.classenrollment.course.api;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class CourseQueryControllerTest {
    @Test void invalidGradeRejectedBeforeDatabaseAccess() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        assertThrows(ResponseStatusException.class,()->new CourseQueryController(jdbc).list(2026,2,"CS",0,20,5,""));
        verifyNoInteractions(jdbc);
    }
    @Test @SuppressWarnings("unchecked") void searchUsesBoundLiteralKeywordAndPagination() {
        JdbcTemplate jdbc=mock(JdbcTemplate.class);
        when(jdbc.query(anyString(),any(RowMapper.class),any(Object[].class))).thenAnswer(invocation->{
            String query=invocation.getArgument(0);
            assertTrue(query.contains("target_grade=?"));assertTrue(query.contains("name LIKE ? ESCAPE '!'"));
            assertFalse(query.contains("50%"));
            Object[] args=invocation.getArguments();
            // Mockito exposes expanded varargs in getArguments().
            assertEquals("%50!%!_!!%",args[7]);
            return List.of(new CourseQueryController.Item(1,"A","A","P","CS",3,100,0,2),
                new CourseQueryController.Item(2,"B","B","P","CS",3,100,0,2));
        });
        var page=new CourseQueryController(jdbc).list(2026,2,"CS",0,1,2,"50%_!");
        assertTrue(page.hasNext());assertEquals(1,page.items().size());assertEquals(1L,page.nextAfterId());
    }
}
