package com.jiyun.classenrollment.course.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;

@RestController
@RequestMapping("/api/courses")
public class CourseQueryController {
    private final JdbcTemplate jdbc;
    public CourseQueryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping
    public Page list(@RequestParam(defaultValue="2026") int year,
                     @RequestParam(defaultValue="2") int semester,
                     @RequestParam(defaultValue="CS") String department,
                     @RequestParam(defaultValue="0") long afterId,
                     @RequestParam(defaultValue="20") int size) {
        if (year < 2000 || year > 2100 || semester < 1 || semester > 2
                || department.isBlank() || department.length() > 50 || afterId < 0 || size < 1 || size > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid course search parameters");
        }
        List<Item> items = jdbc.query("""
            SELECT id, course_code, name, professor, department, credits, capacity, enrolled_count
            FROM courses WHERE academic_year=? AND semester=? AND department=? AND id>?
            ORDER BY id LIMIT ?
            """, (rs, row) -> new Item(rs.getLong("id"), rs.getString("course_code"), rs.getString("name"),
                rs.getString("professor"), rs.getString("department"), rs.getInt("credits"),
                rs.getInt("capacity"), rs.getInt("enrolled_count")), year, semester, department, afterId, size+1);
        boolean more = items.size() > size;
        List<Item> page = List.copyOf(items.subList(0, Math.min(size, items.size())));
        Long cursor = more ? page.get(page.size()-1).id() : null;
        return new Page(page, more, cursor);
    }
    public record Item(long id, String code, String name, String professor, String department,
                       int credits, int capacity, int enrolledCount) { }
    public record Page(List<Item> items, boolean hasNext, Long nextAfterId) { }
}
