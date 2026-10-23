package com.jiyun.classenrollment.course.api;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.util.List;
import java.util.ArrayList;

@RestController
@RequestMapping("/api/courses")
public class CourseQueryController {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    @org.springframework.beans.factory.annotation.Qualifier("catalogJdbcTemplate")
    private JdbcTemplate catalogJdbc;
    @org.springframework.beans.factory.annotation.Autowired
    private com.jiyun.classenrollment.course.application.CourseSearchCache searchCache;
    private final JdbcTemplate jdbc;
    public CourseQueryController(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    @GetMapping
    public Page list(@RequestParam(defaultValue="2026") int year,
                     @RequestParam(defaultValue="2") int semester,
                     @RequestParam(defaultValue="CS") String department,
                     @RequestParam(defaultValue="0") long afterId,
                     @RequestParam(defaultValue="20") int size,
                     @RequestParam(required=false) Integer grade,
                     @RequestParam(defaultValue="") String keyword) {
        if (year < 2000 || year > 2100 || semester < 1 || semester > 2
                || department.isBlank() || department.length() > 50 || afterId < 0 || size < 1 || size > 100
                || (grade != null && (grade < 1 || grade > 4)) || keyword.length() > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid course search parameters");
        }
        // Length-prefixed strings avoid collisions between search fields.
        String key=year+":"+semester+":"+department.length()+":"+department+":"+afterId+":"+size+":"+grade+":"+keyword.strip();
        return searchCache==null?load(year,semester,department,afterId,size,grade,keyword):
            searchCache.get(key,()->load(year,semester,department,afterId,size,grade,keyword));
    }
    private Page load(int year,int semester,String department,long afterId,int size,Integer grade,String keyword) {
        StringBuilder query = new StringBuilder("""
            SELECT id, course_code, name, professor, department, credits, capacity, enrolled_count, target_grade
            FROM courses WHERE academic_year=? AND semester=? AND department=? AND id>?
            """);
        List<Object> params = new ArrayList<>(List.of(year, semester, department, afterId));
        if (grade != null) { query.append(" AND target_grade=?"); params.add(grade); }
        if (!keyword.isBlank()) {
            query.append(" AND name LIKE ? ESCAPE '!'");
            params.add("%" + keyword.strip().replace("!", "!!").replace("%", "!%")
                    .replace("_", "!_") + "%");
        }
        query.append(" ORDER BY id LIMIT ?"); params.add(size + 1);
        List<Item> items = (catalogJdbc == null ? jdbc : catalogJdbc).query(query.toString(), (rs, row) -> new Item(rs.getLong("id"), rs.getString("course_code"), rs.getString("name"),
                rs.getString("professor"), rs.getString("department"), rs.getInt("credits"),
                rs.getInt("capacity"), rs.getInt("enrolled_count"), rs.getInt("target_grade")), params.toArray());
        boolean more = items.size() > size;
        List<Item> page = List.copyOf(items.subList(0, Math.min(size, items.size())));
        Long cursor = more ? page.get(page.size()-1).id() : null;
        return new Page(page, more, cursor);
    }
    public record Item(long id, String code, String name, String professor, String department,
                       int credits, int capacity, int enrolledCount, int grade) { }
    public record Page(List<Item> items, boolean hasNext, Long nextAfterId) { }
}
