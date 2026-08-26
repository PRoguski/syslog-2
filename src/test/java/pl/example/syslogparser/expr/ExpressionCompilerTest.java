package pl.example.syslogparser.expr;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExpressionCompilerTest {

    private static final Pattern SAMPLE =
            Pattern.compile("(?<pid>\\d+)\\|(?<ts>[^|]+)\\|(?<tz>\\w+)");

    private final Filters filters = new Filters(Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC));
    private final Set<String> groups = SAMPLE.namedGroups().keySet();

    @Test
    void plainStringWithoutBracesIsALiteral() {
        Value v = ExpressionCompiler.compile("cisco-8000", groups, filters, "r", "f");
        assertThat(v).isInstanceOf(Value.Literal.class);
        assertThat(v.eval(null)).isEqualTo("cisco-8000");
    }

    @Test
    void bareGroupReferenceEvaluatesToItsCapturedString() {
        Value v = ExpressionCompiler.compile("{{ pid }}", groups, filters, "r", "f");
        assertThat(v.eval(ctx("402|Aug 26 10:14:22.531|UTC"))).isEqualTo("402");
    }

    @Test
    void pipelineAppliesFiltersInOrderAndTracksTheOutputType() {
        Value v = ExpressionCompiler.compile("{{ pid | int }}", groups, filters, "r", "f");
        assertThat(v.eval(ctx("402|Aug 26 10:14:22.531|UTC"))).isEqualTo(402L);
    }

    @Test
    void filterArgumentCanBeAQuotedLiteralOrAReferenceToAnotherGroup() {
        Value v = ExpressionCompiler.compile(
                "{{ ts | parse_ts('MMM ppd HH:mm:ss.SSS', tz) }}", groups, filters, "r", "f");
        assertThat(v.eval(ctx("402|Aug 26 10:14:22.531|UTC")))
                .isEqualTo(Instant.parse("2026-08-26T10:14:22.531Z"));
    }

    @Test
    void builtinSourceRawIsAvailableWithoutBeingARegexGroup() {
        Value v = ExpressionCompiler.compile("{{ raw }}", groups, filters, "r", "f");
        assertThat(v.eval(ctx("402|Aug 26 10:14:22.531|UTC"))).isEqualTo("402|Aug 26 10:14:22.531|UTC");
    }

    @Test
    void nowIsAZeroArgProducerCallNotAGroup() {
        Value v = ExpressionCompiler.compile("{{ now() }}", groups, filters, "r", "f");
        assertThat(v.eval(ctx("402|Aug 26 10:14:22.531|UTC"))).isEqualTo(Instant.parse("2026-08-26T12:00:00Z"));
    }

    @Test
    void unknownGroupFailsCompilationWithTheAvailableGroupsListed() {
        assertThatThrownBy(() -> ExpressionCompiler.compile("{{ iface }}", groups, filters, "xr_link_updown", "interface"))
                .hasMessageContaining("unknown group 'iface'")
                .hasMessageContaining("pid");
    }

    @Test
    void unknownFilterFailsCompilation() {
        assertThatThrownBy(() -> ExpressionCompiler.compile("{{ pid | pri_facilty }}", groups, filters, "r", "f"))
                .hasMessageContaining("unknown filter 'pri_facilty'");
    }

    @Test
    void typeMismatchBetweenChainedFiltersFailsCompilation() {
        // after `int`, the value is a Long; `lower` only accepts String
        assertThatThrownBy(() -> ExpressionCompiler.compile("{{ pid | int | lower }}", groups, filters, "r", "f"))
                .hasMessageContaining("expects String but receives Long");
    }

    @Test
    void wrongArgumentCountFailsCompilation() {
        assertThatThrownBy(() -> ExpressionCompiler.compile("{{ pid | eq }}", groups, filters, "r", "f"))
                .hasMessageContaining("expects 1 argument(s), got 0");
    }

    private Context ctx(String raw) {
        Matcher m = SAMPLE.matcher(raw);
        assertThat(m.matches()).isTrue();
        return Context.of(m, groups, raw, new KafkaMeta(0, 0L, Instant.EPOCH),
                Clock.fixed(Instant.parse("2026-08-26T12:00:00Z"), ZoneOffset.UTC));
    }
}
