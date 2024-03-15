/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.sql.planner;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.collect.ImmutableList;
import io.trino.Session;
import io.trino.cache.CacheUtils;
import io.trino.metadata.ResolvedFunction;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.type.BigintType;
import io.trino.spi.type.BooleanType;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DateType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.DoubleType;
import io.trino.spi.type.IntegerType;
import io.trino.spi.type.RealType;
import io.trino.spi.type.SmallintType;
import io.trino.spi.type.TimeType;
import io.trino.spi.type.TimeWithTimeZoneType;
import io.trino.spi.type.TimestampType;
import io.trino.spi.type.TimestampWithTimeZoneType;
import io.trino.spi.type.TinyintType;
import io.trino.spi.type.Type;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import io.trino.sql.InterpretedFunctionInvoker;
import io.trino.sql.PlannerContext;
import io.trino.sql.ir.Expression;
import io.trino.sql.ir.GenericLiteral;
import io.trino.sql.ir.IrVisitor;
import io.trino.sql.ir.Literal;
import io.trino.sql.ir.NullLiteral;
import io.trino.type.IntervalDayTimeType;
import io.trino.type.IntervalYearMonthType;
import io.trino.type.JsonType;

import java.util.function.Function;

import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.cache.SafeCaches.buildNonEvictableCache;
import static io.trino.spi.type.VarcharType.VARCHAR;
import static io.trino.type.DateTimes.parseTime;
import static io.trino.type.DateTimes.parseTimeWithTimeZone;
import static io.trino.type.DateTimes.parseTimestamp;
import static io.trino.type.DateTimes.parseTimestampWithTimeZone;
import static java.util.Objects.requireNonNull;

public final class IrLiteralInterpreter
{
    private final PlannerContext plannerContext;
    private final ConnectorSession connectorSession;
    private final InterpretedFunctionInvoker functionInvoker;

    private final Cache<Type, Function<GenericLiteral, Object>> genericLiteralEvaluatorCache = buildNonEvictableCache(CacheBuilder.newBuilder().maximumSize(1000));

    public IrLiteralInterpreter(PlannerContext plannerContext, Session session)
    {
        this.plannerContext = requireNonNull(plannerContext, "plannerContext is null");
        this.connectorSession = session.toConnectorSession();
        this.functionInvoker = new InterpretedFunctionInvoker(plannerContext.getFunctionManager());
    }

    public Object evaluate(Expression node, Type type)
    {
        if (!(node instanceof Literal)) {
            throw new IllegalArgumentException("node must be a Literal");
        }
        return new LiteralVisitor(type).process(node, null);
    }

    private class LiteralVisitor
            extends IrVisitor<Object, Void>
    {
        private final Type type;

        private LiteralVisitor(Type type)
        {
            this.type = requireNonNull(type, "type is null");
        }

        @Override
        protected Object visitLiteral(Literal node, Void context)
        {
            throw new UnsupportedOperationException("Unhandled literal type: " + node);
        }

        @Override
        protected Object visitGenericLiteral(GenericLiteral node, Void context)
        {
            return switch (type) {
                case BooleanType type -> node.getRawValue();
                case TinyintType type -> node.getRawValue();
                case SmallintType type -> node.getRawValue();
                case IntegerType type -> node.getRawValue();
                case BigintType type -> node.getRawValue();
                case RealType type -> node.getRawValue();
                case DoubleType type -> node.getRawValue();
                case DecimalType type -> node.getRawValue();
                case VarcharType type -> node.getRawValue();
                case CharType type -> node.getRawValue();
                case DateType type -> node.getRawValue();
                case VarbinaryType type -> node.getRawValue();
                case IntervalYearMonthType type -> node.getRawValue();
                case IntervalDayTimeType type -> node.getRawValue();
                case JsonType type -> node.getRawValue();
                case TimeType unused -> parseTime(node.getValue());
                case TimeWithTimeZoneType value -> parseTimeWithTimeZone(value.getPrecision(), node.getValue());
                case TimestampType value -> parseTimestamp(value.getPrecision(), node.getValue());
                case TimestampWithTimeZoneType value -> parseTimestampWithTimeZone(value.getPrecision(), node.getValue());
                default -> {
                    Function<GenericLiteral, Object> evaluator = CacheUtils.uncheckedCacheGet(genericLiteralEvaluatorCache, type, () -> {
                        ResolvedFunction resolvedFunction = plannerContext.getMetadata().getCoercion(VARCHAR, type);
                        return evaluatedNode -> functionInvoker.invoke(resolvedFunction, connectorSession, ImmutableList.of(utf8Slice(evaluatedNode.getValue())));
                    });
                    yield evaluator.apply(node);
                }
            };
        }

        @Override
        protected Object visitNullLiteral(NullLiteral node, Void context)
        {
            return null;
        }
    }
}