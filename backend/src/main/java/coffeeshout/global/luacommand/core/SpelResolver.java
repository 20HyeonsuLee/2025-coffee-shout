package coffeeshout.global.luacommand.core;

import java.lang.reflect.Method;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

/**
 * 어노테이션의 {@code "#{...}"} 템플릿을 메서드 인자 기반으로 해석한다.
 *
 * <p>지원 문법:
 * <ul>
 *   <li>{@code #a0, #a1, ...} — 인덱스 기반 파라미터</li>
 *   <li>{@code #paramName} — 파라미터 이름 기반 (debug info 필요)</li>
 *   <li>{@code #args[n]} — 인자 배열 인덱스 기반</li>
 *   <li>정적 프로퍼티, 메서드 호출 등 표준 SpEL 전부</li>
 * </ul>
 *
 * <p>표현식 결과가 {@code Collection}이나 배열이면 원본을 그대로 반환해 호출자가 flatten한다.
 */
public final class SpelResolver {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParserContext TEMPLATE_CONTEXT = new TemplateParserContext();
    private static final ParameterNameDiscoverer DISCOVERER = new DefaultParameterNameDiscoverer();

    private SpelResolver() {
    }

    public static Object resolve(final String template, final Method method, final Object[] args) {
        if (template == null || template.isEmpty()) {
            return "";
        }
        if (!template.contains("#{")) {
            return template;
        }

        final StandardEvaluationContext ctx = buildContext(method, args);
        final Expression expression = PARSER.parseExpression(template, TEMPLATE_CONTEXT);
        return expression.getValue(ctx);
    }

    private static StandardEvaluationContext buildContext(final Method method, final Object[] args) {
        final StandardEvaluationContext ctx = new StandardEvaluationContext();
        for (int i = 0; i < args.length; i++) {
            ctx.setVariable("a" + i, args[i]);
        }
        ctx.setVariable("args", args);

        final String[] paramNames = DISCOVERER.getParameterNames(method);
        if (paramNames != null) {
            for (int i = 0; i < paramNames.length && i < args.length; i++) {
                if (paramNames[i] != null) {
                    ctx.setVariable(paramNames[i], args[i]);
                }
            }
        }
        return ctx;
    }
}
