package io.jrdi.mybatis;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.expr.StringLiteralExpr;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.source.AstBuilder;
import io.jrdi.storage.Db;
import io.jrdi.storage.repo.MybatisStatementRepo;
import io.jrdi.storage.repo.MybatisStatementRepo.Kind;
import io.jrdi.storage.repo.sqlite.SqliteRepos;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.insert.Insert;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.update.Update;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MyBatis analyzer: scans a Mapper interface for {@code @Select}/{@code @Insert}/{@code @Update}/{@code @Delete}
 * annotations, parses the SQL via JSqlParser, and persists a normalized statement
 * alongside the original template.
 *
 * <p>M1 scope: annotations only. {@code Mapper.xml} parsing is post-M1 (0.2.0+) since
 * real-world XML uses dynamic tags ({@code <if>}, {@code <foreach>}, {@code <choose>})
 * that need a different parser (MyBatis dynamic-SQL AST, not JSqlParser).
 *
 * <p>M1 limitations:
 * <ul>
 *   <li>No {@code Mapper.xml} ({@code <select>}, {@code <insert>}, ...).
 *   <li>No {@code <resultMap>} or {@code <result>} discovery.
 *   <li>No {@code @SelectProvider} / {@code @InsertProvider} / SQL-builder DSLs.
 *       (P2.7++ DOES record the provider binding; runtime SQL still unattainable.)
 *   <li>No {@code <cache>} / {@code <cache-ref>} / second-level cache wiring.
 *   <li>No {@code @TypeHandler} / {@code @EnumOrdinalTypeHandler} resolution.
 *   <li>No detection of {@code PageHelper.startPage(...)} / physical paging.
 * </ul>
 */
public final class MybatisPass {

    private static final Logger LOG = LoggerFactory.getLogger(MybatisPass.class);

    public static final String SELECT = "org.apache.ibatis.annotations.Select";
    public static final String INSERT = "org.apache.ibatis.annotations.Insert";
    public static final String UPDATE = "org.apache.ibatis.annotations.Update";
    public static final String DELETE = "org.apache.ibatis.annotations.Delete";
    public static final String SELECT_PROVIDER = "org.apache.ibatis.annotations.SelectProvider";
    public static final String INSERT_PROVIDER = "org.apache.ibatis.annotations.InsertProvider";
    public static final String UPDATE_PROVIDER = "org.apache.ibatis.annotations.UpdateProvider";
    public static final String DELETE_PROVIDER = "org.apache.ibatis.annotations.DeleteProvider";

    private static final Pattern KIND_PATTERN = Pattern.compile("^\\s*\\b(SELECT|INSERT|UPDATE|DELETE)\\b",
            Pattern.CASE_INSENSITIVE);

    private final Db db;
    private final AstBuilder ast;

    public MybatisPass(Db db) {
        this(db, new AstBuilder());
    }

    public MybatisPass(Db db, AstBuilder ast) {
        this.db = db;
        this.ast = ast;
    }

    /**
     * Scan a Mapper interface. The {@code owner} FQN is the fully qualified interface
     * name; it doubles as the MyBatis namespace.
     */
    public Result scan(String source, Fqn owner) {
        Optional<CompilationUnit> cuOpt = ast.parse(source);
        if (cuOpt.isEmpty()) return new Result(0);
        CompilationUnit cu = cuOpt.get();
        MybatisStatementRepo repo = SqliteRepos.mybatisStatementRepo(db);
        int recorded = 0;
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!(td instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cd)) continue;
            String namespace = owner.dotted();
            for (MethodDeclaration md : cd.getMethods()) {
                for (AnnotationExpr ann : md.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    String sql = stringValue(ann).orElse(null);
                    if (sql == null) continue;
                    Kind kind = kindFromAnnotation(annName);
                    if (kind == null) continue;
                    String normalized = normalize(sql);
                    int line = md.getBegin().map(p -> p.line).orElse(-1);
                    repo.upsert(namespace, md.getNameAsString(), kind,
                            sql, normalized, List.of(), source, line, "", "");
                    recorded++;
                }
            }
        }
        // Second pass: @XxxProvider annotations. The SQL is generated at
        // runtime by a provider class; we just record the binding
        // (provider class FQN + method name) so the LLM can find the
        // SQL generation logic.
        for (TypeDeclaration<?> td : cu.getTypes()) {
            if (!(td instanceof com.github.javaparser.ast.body.ClassOrInterfaceDeclaration cd)) continue;
            String namespace = owner.dotted();
            for (MethodDeclaration md : cd.getMethods()) {
                for (AnnotationExpr ann : md.getAnnotations()) {
                    String annName = ann.getNameAsString();
                    if (!isProviderAnnotation(annName)) continue;
                    Kind kind = kindFromAnnotation(annName);
                    if (kind == null) continue;
                    String providerClass = annTypeValue(ann, "type");
                    String providerMethod = annStringValue(ann, "method");
                    if (providerClass == null || providerClass.isEmpty()) {
                        // Some MyBatis versions allow the type to be inferred from
                        // the method's return type. We can't easily do that
                        // statically, so leave it empty and the LLM will need
                        // to fall back to the source.
                        providerClass = "";
                    }
                    int line = md.getBegin().map(p -> p.line).orElse(-1);
                    // The SQL template is empty; normalized is empty; no params.
                    repo.upsert(namespace, md.getNameAsString(), kind,
                            "", "", List.of(), source, line,
                            providerClass, providerMethod == null ? "" : providerMethod);
                    recorded++;
                }
            }
        }
        return new Result(recorded);
    }

    /**
     * Extract the value of a {@code Class}-typed attribute from an annotation,
     * e.g. {@code @SelectProvider(type = OwnerSqlProvider.class, ...)} returns
     * "com.acme.OwnerSqlProvider".
     */
    private static String annTypeValue(AnnotationExpr ann, String attribute) {
        if (ann instanceof NormalAnnotationExpr ne) {
            for (var p : ne.getPairs()) {
                if (attribute.equals(p.getNameAsString())) {
                    Expression v = p.getValue();
                    if (v instanceof com.github.javaparser.ast.expr.ClassExpr ce) {
                        return ce.getType().asString();
                    }
                }
            }
        } else if (ann instanceof SingleMemberAnnotationExpr sm) {
            if (attribute.equals("value")) {
                Expression v = sm.getMemberValue();
                if (v instanceof com.github.javaparser.ast.expr.ClassExpr ce) {
                    return ce.getType().asString();
                }
            }
        }
        return null;
    }

    /**
     * Extract a {@code String}-valued attribute from an annotation.
     * Returns the literal text or null if not present.
     */
    private static String annStringValue(AnnotationExpr ann, String attribute) {
        if (ann instanceof NormalAnnotationExpr ne) {
            for (var p : ne.getPairs()) {
                if (attribute.equals(p.getNameAsString())
                        && p.getValue() instanceof StringLiteralExpr sl) {
                    return sl.getValue();
                }
            }
        }
        return null;
    }

    private static Optional<String> stringValue(AnnotationExpr ann) {
        if (ann instanceof SingleMemberAnnotationExpr sm) {
            Expression v = sm.getMemberValue();
            if (v instanceof StringLiteralExpr sl) return Optional.of(sl.getValue());
            return Optional.empty();
        }
        if (ann instanceof NormalAnnotationExpr ne) {
            // Prefer "value" attribute, fall back to "sql"
            for (var attr : List.of("value", "sql")) {
                for (var p : ne.getPairs()) {
                    if (attr.equals(p.getNameAsString())) {
                        Expression v = p.getValue();
                        if (v instanceof StringLiteralExpr sl) return Optional.of(sl.getValue());
                    }
                }
            }
        }
        return Optional.empty();
    }

    private static Kind kindFromAnnotation(String fqn) {
        if (fqn.equals(SELECT) || fqn.equals("Select") || fqn.endsWith(".Select")) return Kind.SELECT;
        if (fqn.equals(INSERT) || fqn.equals("Insert") || fqn.endsWith(".Insert")) return Kind.INSERT;
        if (fqn.equals(UPDATE) || fqn.equals("Update") || fqn.endsWith(".Update")) return Kind.UPDATE;
        if (fqn.equals(DELETE) || fqn.equals("Delete") || fqn.endsWith(".Delete")) return Kind.DELETE;
        if (fqn.equals(SELECT_PROVIDER) || fqn.equals("SelectProvider") || fqn.endsWith(".SelectProvider")) return Kind.SELECT;
        if (fqn.equals(INSERT_PROVIDER) || fqn.equals("InsertProvider") || fqn.endsWith(".InsertProvider")) return Kind.INSERT;
        if (fqn.equals(UPDATE_PROVIDER) || fqn.equals("UpdateProvider") || fqn.endsWith(".UpdateProvider")) return Kind.UPDATE;
        if (fqn.equals(DELETE_PROVIDER) || fqn.equals("DeleteProvider") || fqn.endsWith(".DeleteProvider")) return Kind.DELETE;
        return null;
    }

    /** True for {@code @XxxProvider}-family annotations. */
    private static boolean isProviderAnnotation(String fqn) {
        return fqn.equals(SELECT_PROVIDER) || fqn.equals("SelectProvider") || fqn.endsWith(".SelectProvider")
            || fqn.equals(INSERT_PROVIDER) || fqn.equals("InsertProvider") || fqn.endsWith(".InsertProvider")
            || fqn.equals(UPDATE_PROVIDER) || fqn.equals("UpdateProvider") || fqn.endsWith(".UpdateProvider")
            || fqn.equals(DELETE_PROVIDER) || fqn.equals("DeleteProvider") || fqn.endsWith(".DeleteProvider");
    }

    /**
     * Normalize: strip comments, collapse whitespace, drop trailing semicolons, run JSqlParser
     * to validate. If parsing fails we still store the raw normalised whitespace-collapsed
     * form and skip the AST-derived kind verification.
     */
    private static String normalize(String sql) {
        String stripped = sql.replaceAll("--.*?(\\r?\\n|$)", " ").replaceAll("/\\*.*?\\*/", " ");
        String collapsed = stripped.replaceAll("\\s+", " ").trim();
        if (collapsed.endsWith(";")) collapsed = collapsed.substring(0, collapsed.length() - 1);
        try {
            Statements parsed = CCJSqlParserUtil.parseStatements(collapsed);
            for (Statement s : parsed.getStatements()) {
                if (s instanceof Select) kindOfStmt(collapsed, "SELECT");
                else if (s instanceof Insert) kindOfStmt(collapsed, "INSERT");
                else if (s instanceof Update) kindOfStmt(collapsed, "UPDATE");
                else if (s instanceof Delete) kindOfStmt(collapsed, "DELETE");
                break;
            }
        } catch (JSQLParserException e) {
            LOG.debug("JSqlParser could not parse SQL: {}", e.getMessage());
        }
        return collapsed;
    }

    private static void kindOfStmt(String sql, String expected) {
        Matcher m = KIND_PATTERN.matcher(sql);
        if (m.find()) {
            String got = m.group(1).toUpperCase();
            if (!got.equals(expected)) {
                LOG.warn("MyBatis statement kind mismatch: declared {} but parsed {} in '{}'",
                        expected, got, sql);
            }
        }
    }

    public record Result(int statementsRecorded) {}
}
