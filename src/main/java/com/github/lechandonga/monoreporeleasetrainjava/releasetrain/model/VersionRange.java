package com.github.lechandonga.monoreporeleasetrainjava.releasetrain.model;

/**
 * 依赖版本区间判定。支持：
 * <ul>
 *   <li>{@code ^1.2.3}：兼容区间，[1.2.3, 2.0.0)（0.x 时为 [0.y.z, 0.(y+1).0)）</li>
 *   <li>{@code ~1.2.3}：补丁兼容区间，[1.2.3, 1.3.0)</li>
 *   <li>{@code >=1.2.3,<2.0.0}：比较器区间，支持 &gt;= &gt; &lt;= &lt; =，逗号或空格分隔</li>
 *   <li>{@code 1.2.3}：精确版本</li>
 * </ul>
 * 预发布版本永远不满足正式版本区间。
 */
public final class VersionRange {

    private final String expression;

    private VersionRange(String expression) {
        this.expression = expression;
    }

    public static VersionRange of(String expression) {
        VersionRange range = new VersionRange(expression == null ? "" : expression.trim());
        range.validate();
        return range;
    }

    private void validate() {
        if (expression.isEmpty()) {
            throw new IllegalArgumentException("版本区间缺失");
        }
        if (expression.startsWith("^") || expression.startsWith("~")) {
            SemanticVersion.parse(expression.substring(1).trim());
            return;
        }
        if (startsComparator()) {
            parseComparators(expression);
            return;
        }
        SemanticVersion.parse(expression);
    }

    private boolean startsComparator() {
        return expression.startsWith(">=") || expression.startsWith("<=")
                || expression.startsWith(">") || expression.startsWith("<")
                || expression.startsWith("=");
    }

    public boolean contains(SemanticVersion version) {
        if (version.isPrerelease()) {
            return false;
        }
        if (expression.startsWith("^")) {
            SemanticVersion low = SemanticVersion.parse(expression.substring(1).trim());
            SemanticVersion high = low.major() > 0
                    ? SemanticVersion.release(low.major() + 1, 0, 0)
                    : SemanticVersion.release(0, low.minor() + 1, 0);
            return version.compareTo(low) >= 0 && version.compareTo(high) < 0;
        }
        if (expression.startsWith("~")) {
            SemanticVersion low = SemanticVersion.parse(expression.substring(1).trim());
            SemanticVersion high = SemanticVersion.release(low.major(), low.minor() + 1, 0);
            return version.compareTo(low) >= 0 && version.compareTo(high) < 0;
        }
        if (startsComparator()) {
            return containsComparators(version);
        }
        return version.compareTo(SemanticVersion.parse(expression)) == 0;
    }

    private boolean containsComparators(SemanticVersion version) {
        for (String token : expression.split("[,\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            String op;
            String operand;
            if (token.startsWith(">=") || token.startsWith("<=")) {
                op = token.substring(0, 2);
                operand = token.substring(2);
            } else if (token.startsWith(">") || token.startsWith("<") || token.startsWith("=")) {
                op = token.substring(0, 1);
                operand = token.substring(1);
            } else {
                throw new IllegalArgumentException("非法版本区间: " + expression);
            }
            SemanticVersion bound = SemanticVersion.parse(operand.trim());
            int cmp = version.compareTo(bound);
            boolean ok = switch (op) {
                case ">=" -> cmp >= 0;
                case ">" -> cmp > 0;
                case "<=" -> cmp <= 0;
                case "<" -> cmp < 0;
                default -> cmp == 0;
            };
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    private void parseComparators(String text) {
        boolean any = false;
        for (String token : text.split("[,\\s]+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.startsWith(">=") || token.startsWith("<=")) {
                SemanticVersion.parse(token.substring(2).trim());
            } else if (token.startsWith(">") || token.startsWith("<") || token.startsWith("=")) {
                SemanticVersion.parse(token.substring(1).trim());
            } else {
                throw new IllegalArgumentException("非法版本区间: " + expression);
            }
            any = true;
        }
        if (!any) {
            throw new IllegalArgumentException("非法版本区间: " + expression);
        }
    }

    public String expression() {
        return expression;
    }

    @Override
    public String toString() {
        return expression;
    }
}
