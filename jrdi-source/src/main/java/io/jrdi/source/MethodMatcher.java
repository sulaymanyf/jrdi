package io.jrdi.source;

import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.expr.LambdaExpr;
import com.github.javaparser.ast.stmt.BlockStmt;
import io.jrdi.core.symbol.Fqn;
import io.jrdi.core.symbol.MethodKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Top-level entry: given source text and a class FQN + method key, return
 * {@link SourceFacts} with the start/end line numbers of the method and the
 * (best-effort) line number of each lambda inside it.
 */
public final class MethodMatcher {

    private static final Logger LOG = LoggerFactory.getLogger(MethodMatcher.class);

    private final AstBuilder astBuilder;

    public MethodMatcher() {
        this(new AstBuilder());
    }

    public MethodMatcher(AstBuilder astBuilder) {
        this.astBuilder = astBuilder;
    }

    public Optional<SourceFacts> match(String source, Fqn owner, MethodKey key) {
        Optional<CompilationUnit> cuOpt = astBuilder.parse(source);
        if (cuOpt.isEmpty()) return Optional.empty();
        CompilationUnit cu = cuOpt.get();
        Optional<TypeDeclaration<?>> typeOpt = astBuilder.findType(cu, owner);
        if (typeOpt.isEmpty()) {
            LOG.debug("type {} not found in source", owner);
            return Optional.empty();
        }
        Optional<MethodDeclaration> methodOpt = astBuilder.findMethod(typeOpt.get(), key);
        if (methodOpt.isEmpty()) {
            LOG.debug("method {}#{} not found in source", owner, key);
            return Optional.empty();
        }
        MethodDeclaration md = methodOpt.get();
        int start = md.getBegin().map(p -> p.line).orElse(-1);
        int end = md.getEnd().map(p -> p.line).orElse(-1);
        List<LambdaHit> lambdas = findLambdasInBody(md);
        return Optional.of(new SourceFacts(start, end, lambdas));
    }

    public List<LambdaHit> findLambdasInBody(MethodDeclaration md) {
        List<LambdaHit> out = new ArrayList<>();
        if (!md.getBody().isPresent()) return out;
        BlockStmt body = md.getBody().get();
        for (LambdaExpr lambda : body.findAll(LambdaExpr.class)) {
            int line = lambda.getBegin().map(p -> p.line).orElse(-1);
            // Try to extract the SAM signature (parameter types) — best-effort.
            out.add(new LambdaHit(line, lambda.getParameters().toString()));
        }
        return out;
    }

    public record SourceFacts(int startLine, int endLine, List<LambdaHit> lambdas) {
        public boolean hasLines() {
            return startLine > 0 && endLine >= startLine;
        }
    }

    public record LambdaHit(int line, String paramSummary) {}
}
