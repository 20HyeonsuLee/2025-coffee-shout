package coffeeshout.global.lock;

import java.lang.reflect.Method;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.ParserContext;
import org.springframework.expression.common.TemplateParserContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

final class SpelTemplateResolver {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final ParserContext TEMPLATE_CONTEXT = new TemplateParserContext();
    private static final ParameterNameDiscoverer DISCOVERER = new DefaultParameterNameDiscoverer();

    private SpelTemplateResolver() {
    }

    static Object resolve(final String template, final Method method, final Object[] args) {
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
