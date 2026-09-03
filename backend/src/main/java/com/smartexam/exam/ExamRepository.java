package com.smartexam.exam;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartexam.exam.ExamModels.*;
import com.smartexam.question.QuestionModels.QuestionView;
import com.smartexam.question.QuestionRepository;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class ExamRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final QuestionRepository questions;

    public ExamRepository(JdbcClient jdbc, ObjectMapper json, QuestionRepository questions) {
        this.jdbc = jdbc; this.json = json; this.questions = questions;
    }

    public long createPaper(PaperRequest request, long teacherId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO paper(name,duration_minutes,total_score,status,created_by) VALUES (:name,:duration,:total,'DRAFT',:teacher)")
                .param("name", request.name().trim()).param("duration", request.durationMinutes())
                .param("total", request.totalScore()).param("teacher", teacherId).update(keys, "id");
        long paperId = keys.getKey().longValue();
        for (int i = 0; i < request.questions().size(); i++) {
            PaperQuestionRequest item = request.questions().get(i);
            QuestionView question = questions.findById(item.questionId()).orElseThrow();
            jdbc.sql("""
                    INSERT INTO paper_question(paper_id,question_id,display_order,score,type_snapshot,stem_snapshot,
                      options_snapshot,answer_snapshot,explanation_snapshot)
                    VALUES (:paper,:question,:position,:score,:type,:stem,:options,:answer,:explanation)
                    """).param("paper", paperId).param("question", question.id()).param("position", i + 1)
                    .param("score", item.score()).param("type", question.type().name()).param("stem", question.stem())
                    .param("options", json.valueToTree(question.options()).toString())
                    .param("answer", question.standardAnswer().toString()).param("explanation", question.explanation()).update();
        }
        return paperId;
    }

    public Optional<PaperView> findPaper(long id) {
        return jdbc.sql("SELECT id,name,duration_minutes,total_score,status,created_by FROM paper WHERE id=:id")
                .param("id", id).query((rs, row) -> new PaperView(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("duration_minutes"), rs.getBigDecimal("total_score"), rs.getString("status"),
                        rs.getLong("created_by"), findPaperQuestions(rs.getLong("id")))).optional();
    }

    public List<PaperView> findPapers(long teacherId) {
        return jdbc.sql("SELECT id,name,duration_minutes,total_score,status,created_by FROM paper WHERE created_by=:teacher ORDER BY id DESC")
                .param("teacher", teacherId).query((rs, row) -> new PaperView(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("duration_minutes"), rs.getBigDecimal("total_score"), rs.getString("status"),
                        rs.getLong("created_by"), findPaperQuestions(rs.getLong("id")))).list();
    }

    public List<PaperQuestionView> findPaperQuestions(long paperId) {
        return jdbc.sql("""
                SELECT id,question_id,display_order,score,type_snapshot,stem_snapshot,options_snapshot
                FROM paper_question WHERE paper_id=:paper ORDER BY display_order
                """).param("paper", paperId).query((rs, row) -> new PaperQuestionView(rs.getLong("id"),
                        rs.getLong("question_id"), rs.getInt("display_order"), rs.getBigDecimal("score"),
                        rs.getString("type_snapshot"), rs.getString("stem_snapshot"), readJson(rs.getString("options_snapshot")))).list();
    }

    public void publishPaper(long id) {
        jdbc.sql("UPDATE paper SET status='PUBLISHED' WHERE id=:id").param("id", id).update();
    }

    public long createExam(ExamRequest request, long teacherId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO exam(name,paper_id,created_by,start_at,end_at,status) VALUES (:name,:paper,:teacher,:start,:end,'DRAFT')")
                .param("name", request.name().trim()).param("paper", request.paperId()).param("teacher", teacherId)
                .param("start", request.startAt()).param("end", request.endAt()).update(keys, "id");
        return keys.getKey().longValue();
    }

    public Optional<ExamView> findExam(long id, Long studentId) {
        String sql = examSelect() + " WHERE e.id=:id";
        JdbcClient.StatementSpec statement = jdbc.sql(sql).param("id", id).param("student", studentId == null ? -1L : studentId);
        return statement.query(this::mapExam).optional();
    }

    public List<ExamView> findTeacherExams(long teacherId) {
        return jdbc.sql(examSelect() + " WHERE e.created_by=:teacher ORDER BY e.id DESC")
                .param("teacher", teacherId).param("student", -1L).query(this::mapExam).list();
    }

    public List<ExamView> findStudentExams(long studentId) {
        return jdbc.sql(examSelect() + " WHERE e.status='PUBLISHED' ORDER BY e.start_at,e.id")
                .param("student", studentId).query(this::mapExam).list();
    }

    public void publishExam(long id) { jdbc.sql("UPDATE exam SET status='PUBLISHED' WHERE id=:id").param("id", id).update(); }

    public Optional<SubmissionView> findSubmission(long id) {
        return jdbc.sql("SELECT id,exam_id,status,started_at,submitted_at,objective_score FROM submission WHERE id=:id")
                .param("id", id).query((rs, row) -> new SubmissionView(rs.getLong("id"), rs.getLong("exam_id"),
                        rs.getString("status"), instant(rs, "started_at"), instant(rs, "submitted_at"),
                        rs.getBigDecimal("objective_score"), findSubmissionQuestions(rs.getLong("exam_id")))).optional();
    }

    public Optional<Long> findSubmissionId(long examId, long studentId) {
        return jdbc.sql("SELECT id FROM submission WHERE exam_id=:exam AND student_id=:student")
                .param("exam", examId).param("student", studentId).query(Long.class).optional();
    }

    public long createSubmission(long examId, long studentId) {
        GeneratedKeyHolder keys = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO submission(exam_id,student_id,status) VALUES (:exam,:student,'IN_PROGRESS')")
                .param("exam", examId).param("student", studentId).update(keys, "id");
        return keys.getKey().longValue();
    }

    public long submissionOwner(long id) {
        return jdbc.sql("SELECT student_id FROM submission WHERE id=:id").param("id", id).query(Long.class).optional().orElse(-1L);
    }

    public void lockSubmission(long id) {
        jdbc.sql("SELECT id FROM submission WHERE id=:id FOR UPDATE").param("id", id).query(Long.class).optional();
    }

    public void saveAnswer(long submissionId, AnswerRequest answer) {
        int updated = jdbc.sql("UPDATE submission_answer SET answer_content=:answer WHERE submission_id=:submission AND paper_question_id=:question")
                .param("answer", answer.answerContent() == null ? "null" : answer.answerContent().toString())
                .param("submission", submissionId).param("question", answer.paperQuestionId()).update();
        if (updated == 0) jdbc.sql("INSERT INTO submission_answer(submission_id,paper_question_id,answer_content) VALUES (:submission,:question,:answer)")
                .param("submission", submissionId).param("question", answer.paperQuestionId())
                .param("answer", answer.answerContent() == null ? "null" : answer.answerContent().toString()).update();
    }

    public boolean questionBelongsToSubmission(long submissionId, long paperQuestionId) {
        return jdbc.sql("""
                SELECT COUNT(*) FROM submission s JOIN exam e ON e.id=s.exam_id
                JOIN paper_question pq ON pq.paper_id=e.paper_id
                WHERE s.id=:submission AND pq.id=:question
                """).param("submission", submissionId).param("question", paperQuestionId).query(Long.class).single() > 0;
    }

    public List<AnswerRow> answersForScoring(long submissionId) {
        return jdbc.sql("""
                SELECT pq.id,pq.type_snapshot,pq.answer_snapshot,pq.score,sa.answer_content
                FROM submission s JOIN exam e ON e.id=s.exam_id JOIN paper_question pq ON pq.paper_id=e.paper_id
                LEFT JOIN submission_answer sa ON sa.submission_id=s.id AND sa.paper_question_id=pq.id
                WHERE s.id=:submission ORDER BY pq.display_order
                """).param("submission", submissionId).query((rs, row) -> new AnswerRow(rs.getLong("id"),
                        rs.getString("type_snapshot"), readJson(rs.getString("answer_snapshot")),
                        rs.getBigDecimal("score"), readJson(rs.getString("answer_content")))).list();
    }

    public void scoreAnswer(long submissionId, long paperQuestionId, BigDecimal score) {
        int updated = jdbc.sql("UPDATE submission_answer SET score=:score WHERE submission_id=:submission AND paper_question_id=:question")
                .param("score", score).param("submission", submissionId).param("question", paperQuestionId).update();
        if (updated == 0) jdbc.sql("INSERT INTO submission_answer(submission_id,paper_question_id,answer_content,score) VALUES (:submission,:question,'null',:score)")
                .param("submission", submissionId).param("question", paperQuestionId).param("score", score).update();
    }

    public void submit(long id, BigDecimal objectiveScore, Instant submittedAt) {
        jdbc.sql("UPDATE submission SET status='SUBMITTED',submitted_at=:at,objective_score=:score,total_score=:score,version=version+1 WHERE id=:id")
                .param("at", submittedAt).param("score", objectiveScore).param("id", id).update();
    }

    public List<Long> findExpiredInProgressSubmissions(Instant now) {
        return jdbc.sql("""
                SELECT s.id FROM submission s JOIN exam e ON e.id=s.exam_id
                WHERE s.status='IN_PROGRESS' AND e.end_at<=:now ORDER BY s.id
                """).param("now", now).query(Long.class).list();
    }

    private List<PaperQuestionView> findSubmissionQuestions(long examId) {
        return jdbc.sql("SELECT paper_id FROM exam WHERE id=:id").param("id", examId).query(Long.class).optional()
                .map(this::findPaperQuestions).orElse(List.of());
    }

    private String examSelect() {
        return """
                SELECT e.id,e.name,e.paper_id,p.name paper_name,p.duration_minutes,p.total_score,e.start_at,e.end_at,
                  e.status,e.created_by,s.id submission_id,s.status submission_status
                FROM exam e JOIN paper p ON p.id=e.paper_id
                LEFT JOIN submission s ON s.exam_id=e.id AND s.student_id=:student
                """;
    }

    private ExamView mapExam(ResultSet rs, int row) throws SQLException {
        long submissionId = rs.getLong("submission_id");
        return new ExamView(rs.getLong("id"), rs.getString("name"), rs.getLong("paper_id"), rs.getString("paper_name"),
                rs.getInt("duration_minutes"), rs.getBigDecimal("total_score"), instant(rs, "start_at"), instant(rs, "end_at"),
                rs.getString("status"), rs.getLong("created_by"), rs.wasNull() ? null : submissionId, rs.getString("submission_status"));
    }

    private Instant instant(ResultSet rs, String column) throws SQLException {
        var timestamp = rs.getTimestamp(column); return timestamp == null ? null : timestamp.toInstant();
    }

    private JsonNode readJson(String value) {
        if (value == null) return null;
        try { return json.readTree(value); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("数据库中的答案不是合法 JSON", exception); }
    }

    public record AnswerRow(long paperQuestionId, String type, JsonNode expected, BigDecimal score, JsonNode actual) {}
}
