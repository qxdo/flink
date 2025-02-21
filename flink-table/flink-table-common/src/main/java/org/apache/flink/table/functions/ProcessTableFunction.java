/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.table.functions;

import org.apache.flink.annotation.PublicEvolving;
import org.apache.flink.table.annotation.ArgumentHint;
import org.apache.flink.table.annotation.ArgumentTrait;
import org.apache.flink.table.annotation.DataTypeHint;
import org.apache.flink.table.annotation.FunctionHint;
import org.apache.flink.table.annotation.StateHint;
import org.apache.flink.table.catalog.DataTypeFactory;
import org.apache.flink.table.types.extraction.TypeInferenceExtractor;
import org.apache.flink.table.types.inference.TypeInference;
import org.apache.flink.util.Collector;

/**
 * Base class for a user-defined process table function. A process table function (PTF) maps zero,
 * one, or multiple tables to zero, one, or multiple rows (or structured types). Scalar arguments
 * are also supported. If the output record consists of only one field, the wrapper can be omitted,
 * and a scalar value can be emitted that will be implicitly wrapped into a row by the runtime.
 *
 * <p>PTFs are the most powerful function kind for Flink SQL and Table API. They enable implementing
 * user-defined operators that can be as feature-rich as built-in operations. PTFs have access to
 * Flink's managed state, event-time and timer services, underlying table changelogs, and can take
 * multiple ordered and/or partitioned tables to produce a new table.
 *
 * <h1>Table Semantics and Virtual Processors</h1>
 *
 * <p>PTFs can produce a new table by consuming tables as arguments. For scalability, input tables
 * are distributed across so-called "virtual processors". A virtual processor, as defined by the SQL
 * standard, executes a PTF instance and has access only to a portion of the entire table. The
 * argument declaration decides about the size of the portion and co-location of data. Conceptually,
 * tables can be processed either "as row" (i.e. with row semantics) or "as set" (i.e. with set
 * semantics).
 *
 * <h2>Table Argument with Row Semantics</h2>
 *
 * <p>A PTF that takes a table with row semantics assumes that there is no correlation between rows
 * and each row can be processed independently. The framework is free in how to distribute rows
 * across virtual processors and each virtual processor has access only to the currently processed
 * row.
 *
 * <h2>Table Argument with Set Semantics</h2>
 *
 * <p>A PTF that takes a table with set semantics assumes that there is a correlation between rows.
 * When calling the function, the PARTITION BY clause defines the columns for correlation. The
 * framework ensures that all rows belonging to same set are co-located. A PTF instance is able to
 * access all rows belonging to the same set. In other words: The virtual processor is scoped by a
 * key context.
 *
 * <p>It is also possible not to provide a key ({@link ArgumentTrait#OPTIONAL_PARTITION_BY}), in
 * which case only one virtual processor handles the entire table, thereby losing scalability
 * benefits.
 *
 * <h1>Implementation</h1>
 *
 * <p>The behavior of a {@link ProcessTableFunction} can be defined by implementing a custom
 * evaluation method. The evaluation method must be declared publicly, not static, and named <code>
 * eval</code>. Overloading is not supported.
 *
 * <p>For storing a user-defined function in a catalog, the class must have a default constructor
 * and must be instantiable during runtime. Anonymous functions in Table API can only be persisted
 * if the function object is not stateful (i.e. containing only transient and static fields).
 *
 * <h2>Data Types</h2>
 *
 * <p>By default, input and output data types are automatically extracted using reflection. This
 * includes the generic argument {@code T} of the class for determining an output data type. Input
 * arguments are derived from the {@code eval()} method. If the reflective information is not
 * sufficient, it can be supported and enriched with {@link FunctionHint}, {@link ArgumentHint}, and
 * {@link DataTypeHint} annotations.
 *
 * <p>The following examples show how to specify data types:
 *
 * <pre>{@code
 * // Function that accepts two scalar INT arguments and emits them as an implicit ROW < INT >
 * class AdditionFunction extends ProcessTableFunction<Integer> {
 *   public void eval(Integer a, Integer b) {
 *     collect(a + b);
 *   }
 * }
 *
 * // Function that produces an explicit ROW < i INT, s STRING > from arguments, the function hint helps in
 * // declaring the row's fields
 * @FunctionHint(output = @DataTypeHint("ROW< i INT, s STRING >"))
 * class DuplicatorFunction extends ProcessTableFunction<Row> {
 *   public void eval(Integer i, String s) {
 *     collect(Row.of(i, s));
 *     collect(Row.of(i, s));
 *   }
 * }
 *
 * // Function that accepts DECIMAL(10, 4) and emits it as an explicit ROW < DECIMAL(10, 4) >
 * @FunctionHint(output = @DataTypeHint("ROW< DECIMAL(10, 4) >"))
 * class DuplicatorFunction extends TableFunction<Row> {
 *   public void eval(@DataTypeHint("DECIMAL(10, 4)") BigDecimal d) {
 *     collect(Row.of(d));
 *     collect(Row.of(d));
 *   }
 * }
 * }</pre>
 *
 * <h2>Arguments</h2>
 *
 * <p>The {@link ArgumentHint} annotation enables declaring the name, data type, and kind of each
 * argument (i.e. ArgumentTrait.SCALAR, ArgumentTrait.TABLE_AS_SET, or ArgumentTrait.TABLE_AS_ROW).
 * It allows specifying other traits for table arguments as well:
 *
 * <pre>{@code
 * // Function that has two arguments:
 * // "input_table" (a table with set semantics) and "threshold" (a scalar value)
 * class ThresholdFunction extends ProcessTableFunction<Integer> {
 *   public void eval(
 *       // For table arguments, a data type for Row is optional (leading to polymorphic behavior)
 *       @ArgumentHint(value = ArgumentTrait.TABLE_AS_SET, name = "input_table") Row t,
 *       // Scalar arguments require a data type either explicit or via reflection
 *       @ArgumentHint(value = ArgumentTrait.SCALAR, name = "threshold") Integer threshold) {
 *     int amount = t.getFieldAs("amount");
 *     if (amount >= threshold) {
 *       collect(amount);
 *     }
 *   }
 * }
 * }</pre>
 *
 * <p>Table arguments can declare a concrete data type (of either row or structured type) or accept
 * any type of row in polymorphic fashion:
 *
 * <pre>{@code
 * // Function with explicit table argument type of row
 * class MyPTF extends ProcessTableFunction<String> {
 *   public void eval(Context ctx, @ArgumentHint(value = ArgumentTrait.TABLE_AS_SET, type = @DataTypeHint("ROW < s STRING >")) Row t) {
 *     TableSemantics semantics = ctx.tableSemanticsFor("t");
 *     // Always returns "ROW < s STRING >"
 *     semantics.dataType();
 *     ...
 *   }
 * }
 *
 * // Function with explicit table argument type of structured type "Customer"
 * class MyPTF extends ProcessTableFunction<String> {
 *   public void eval(Context ctx, @ArgumentHint(value = ArgumentTrait.TABLE_AS_SET) Customer c) {
 *     TableSemantics semantics = ctx.tableSemanticsFor("c");
 *     // Always returns structured type of "Customer"
 *     semantics.dataType();
 *     ...
 *   }
 * }
 *
 * // Function with polymorphic table argument
 * class MyPTF extends ProcessTableFunction<String> {
 *   public void eval(Context ctx, @ArgumentHint(value = ArgumentTrait.TABLE_AS_SET) Row t) {
 *     TableSemantics semantics = ctx.tableSemanticsFor("t");
 *     // Always returns "ROW" but content depends on the table that is passed into the call
 *     semantics.dataType();
 *     ...
 *   }
 * }
 * }</pre>
 *
 * <h2>Context</h2>
 *
 * <p>A {@link Context} can be added as a first argument to the eval() method for additional
 * information about the input tables and other services provided by the framework:
 *
 * <pre>{@code
 * // a function that accesses the Context for reading the PARTITION BY columns and
 * // excluding them when building a result string
 * class ConcatNonKeysFunction extends ProcessTableFunction<String> {
 *   public void eval(Context ctx, @ArgumentHint(ArgumentTrait.TABLE_AS_SET) Row inputTable) {
 *     TableSemantics semantics = ctx.tableSemanticsFor("inputTable");
 *     List<Integer> keys = Arrays.asList(semantics.partitionByColumns());
 *     return IntStream.range(0, inputTable.getArity())
 *       .filter(pos -> !keys.contains(pos))
 *       .mapToObj(inputTable::getField)
 *       .map(Object::toString)
 *       .collect(Collectors.joining(", "));
 *   }
 * }
 * }</pre>
 *
 * <h1>State</h1>
 *
 * <p>A PTF that takes set semantic tables can be stateful. Intermediate results can be buffered,
 * cached, aggregated, or simply stored for repeated access. A function can have one or more state
 * entries which are managed by the framework. Flink takes care of storing and restoring those
 * during failures or restarts (i.e. Flink managed state).
 *
 * <p>A state entry is partitioned by a key and cannot be accessed globally. The partitioning (or a
 * single partition in case of no partitioning) is defined by the corresponding function call. In
 * other words: Similar to how a virtual processor has access only to a portion of the entire table,
 * a PTF has access only to a portion of the entire state defined by the PARTITION BY clause. In
 * Flink, this concept is also known as keyed state.
 *
 * <p>State entries can be added as a mutable parameter to the eval() method. In order to
 * distinguish them from call arguments, they must be declared before any other argument, but after
 * an optional {@link Context} parameter. Furthermore, they must be annotated either via {@link
 * StateHint} or declared as part of {@link FunctionHint#state()}.
 *
 * <p>For read and write access, only row or structured types (i.e. POJOs with default constructor)
 * qualify as a data type. If no state is present, all fields are set to null (in case of a row
 * type) or fields are set to their default value (in case of a structured type). For state
 * efficiency, it is recommended to keep all fields nullable.
 *
 * <pre>{@code
 * // a function that counts and stores its intermediate result in the CountState object
 * // which will be persisted by Flink
 * class CountingFunction extends ProcessTableFunction<String> {
 *   public static class CountState {
 *     public long count = 0L;
 *   }
 *
 *   public void eval(@StateHint CountState memory, @ArgumentHint(TABLE_AS_SET) Row input) {
 *     memory.count++;
 *     collect("Seen rows: " + memory.count);
 *   }
 * }
 *
 * // a function that waits for a second event coming in
 * class CountingFunction extends ProcessTableFunction<String> {
 *   public static class SeenState {
 *     public String first;
 *   }
 *
 *   public void eval(@StateHint SeenState memory, @ArgumentHint(TABLE_AS_SET) Row input) {
 *     if (memory.first == null) {
 *       memory.first = input.toString();
 *     } else {
 *       collect("Event 1: " + memory.first + " and Event 2: " + input.toString());
 *     }
 *   }
 * }
 *
 * // a function that uses Row for state
 * class CountingFunction extends ProcessTableFunction<String> {
 *   public void eval(@StateHint(type = @DataTypeHint("ROW < count BIGINT >")) Row memory, @ArgumentHint(TABLE_AS_SET) Row input) {
 *     Long newCount = 1L;
 *     if (memory.getField("count") != null) {
 *       newCount += memory.getFieldAs("count");
 *     }
 *     memory.setField("count", newCount);
 *     collect("Seen rows: " + newCount);
 *   }
 * }
 * }</pre>
 *
 * <h2>Efficiency and Design Principles</h2>
 *
 * <p>A stateful function also means that data layout and data retention should be well thought
 * through. An ever-growing state can happen by an unlimited number of partitions (i.e. an open
 * keyspace) or even within a partition. Consider setting a {@link StateHint#ttl()} or call {@link
 * Context#clearAllState()} eventually:
 *
 * <pre>{@code
 * // a function that waits for a second event coming in BUT with better state efficiency
 * class CountingFunction extends ProcessTableFunction<String> {
 *   public static class SeenState {
 *     public String first;
 *   }
 *
 *   public void eval(Context ctx, @StateHint(ttl = "1 day") SeenState memory, @ArgumentHint(TABLE_AS_SET) Row input) {
 *     if (memory.first == null) {
 *       memory.first = input.toString();
 *     } else {
 *       collect("Event 1: " + memory.first + " and Event 2: " + input.toString());
 *       ctx.clearAllState();
 *     }
 *   }
 * }
 * }</pre>
 *
 * @param <T> The type of the output row. Either an explicit composite type or an atomic type that
 *     is implicitly wrapped into a row consisting of one field.
 */
@PublicEvolving
public abstract class ProcessTableFunction<T> extends UserDefinedFunction {

    /** The code generated collector used to emit rows. */
    private transient Collector<T> collector;

    /** Internal use. Sets the current collector. */
    public final void setCollector(Collector<T> collector) {
        this.collector = collector;
    }

    /**
     * Emits an (implicit or explicit) output row.
     *
     * <p>If null is emitted as an explicit row, it will be skipped by the runtime. For implicit
     * rows, the row's field will be null.
     *
     * @param row the output row
     */
    protected final void collect(T row) {
        collector.collect(row);
    }

    @Override
    public final FunctionKind getKind() {
        return FunctionKind.PROCESS_TABLE;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public TypeInference getTypeInference(DataTypeFactory typeFactory) {
        return TypeInferenceExtractor.forProcessTableFunction(typeFactory, (Class) getClass());
    }

    /**
     * Context that can be added as a first argument to the eval() method for additional information
     * about the input tables and other services provided by the framework.
     */
    @PublicEvolving
    public interface Context {

        /**
         * Returns additional information about the semantics of a table argument.
         *
         * @param argName name of the table argument; either reflectively extracted or manually
         *     defined via {@link ArgumentHint#name()}.
         */
        TableSemantics tableSemanticsFor(String argName);

        /**
         * Clears the given state entry within the virtual partition once the eval() method returns.
         *
         * <p>Semantically this is equal to setting all fields of the state entry to null shortly
         * before the eval() method returns.
         *
         * @param stateName name of the state entry; either reflectively extracted or manually
         *     defined via {@link StateHint#name()}.
         */
        void clearState(String stateName);

        /**
         * Clears all state entries within the virtual partition once the eval() method returns.
         *
         * <p>Semantically this is equal to calling {@link #clearState(String)} on all state
         * entries.
         */
        void clearAllState();
    }
}
