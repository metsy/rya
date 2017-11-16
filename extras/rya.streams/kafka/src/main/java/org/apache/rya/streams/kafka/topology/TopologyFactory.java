/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.rya.streams.kafka.topology;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.streams.processor.Processor;
import org.apache.kafka.streams.processor.ProcessorSupplier;
import org.apache.kafka.streams.processor.StateStoreSupplier;
import org.apache.kafka.streams.processor.TopologyBuilder;
import org.apache.kafka.streams.state.Stores;
import org.apache.rya.api.function.join.IterativeJoin;
import org.apache.rya.api.function.join.LeftOuterJoin;
import org.apache.rya.api.function.join.NaturalJoin;
import org.apache.rya.api.model.VisibilityBindingSet;
import org.apache.rya.streams.kafka.processors.ProcessorResult;
import org.apache.rya.streams.kafka.processors.ProcessorResult.BinaryResult;
import org.apache.rya.streams.kafka.processors.ProcessorResult.BinaryResult.Side;
import org.apache.rya.streams.kafka.processors.ProcessorResult.UnaryResult;
import org.apache.rya.streams.kafka.processors.StatementPatternProcessorSupplier;
import org.apache.rya.streams.kafka.processors.join.JoinProcessorSupplier;
import org.apache.rya.streams.kafka.processors.output.BindingSetOutputFormatterSupplier;
import org.apache.rya.streams.kafka.processors.projection.ProjectionProcessorSupplier;
import org.apache.rya.streams.kafka.serialization.VisibilityBindingSetSerde;
import org.apache.rya.streams.kafka.serialization.VisibilityBindingSetSerializer;
import org.apache.rya.streams.kafka.serialization.VisibilityStatementDeserializer;
import org.openrdf.query.MalformedQueryException;
import org.openrdf.query.algebra.BinaryTupleOperator;
import org.openrdf.query.algebra.Extension;
import org.openrdf.query.algebra.Join;
import org.openrdf.query.algebra.LeftJoin;
import org.openrdf.query.algebra.Projection;
import org.openrdf.query.algebra.QueryModelNode;
import org.openrdf.query.algebra.StatementPattern;
import org.openrdf.query.algebra.TupleExpr;
import org.openrdf.query.algebra.helpers.QueryModelVisitorBase;
import org.openrdf.query.parser.ParsedQuery;
import org.openrdf.query.parser.sparql.SPARQLParser;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import edu.umd.cs.findbugs.annotations.DefaultAnnotation;
import edu.umd.cs.findbugs.annotations.NonNull;

/**
 * Factory for building {@link TopologyBuilder}s from a SPARQL query.
 */
@DefaultAnnotation(NonNull.class)
public class TopologyFactory implements TopologyBuilderFactory {
    private static final String SOURCE = "SOURCE";
    private static final String STATEMENT_PATTERN_PREFIX = "SP_";
    private static final String JOIN_PREFIX = "JOIN_";
    private static final String PROJECTION_PREFIX = "PROJECTION_";
    private static final String SINK = "SINK";

    private List<ProcessorEntry> processorEntryList;

    /**
     * Builds a {@link TopologyBuilder} based on the provided sparql query.
     *
     * @param sparqlQuery - The SPARQL query to build a topology for. (not null)
     * @param statementTopic - The topic for the source to read from. (not null)
     * @param resultTopic - The topic for the sink to write to. (not null)
     * @return - The created {@link TopologyBuilder}.
     * @throws MalformedQueryException - The provided query is not a valid SPARQL query.
     * @throws TopologyBuilderException - A problem occurred while constructing the topology.
     */
    @Override
    public TopologyBuilder build(final String sparqlQuery, final String statementTopic, final String resultTopic)
            throws MalformedQueryException, TopologyBuilderException {
        requireNonNull(sparqlQuery);
        requireNonNull(statementTopic);
        requireNonNull(resultTopic);

        final ParsedQuery parsedQuery = new SPARQLParser().parseQuery(sparqlQuery, null);
        final TopologyBuilder builder = new TopologyBuilder();

        final TupleExpr expr = parsedQuery.getTupleExpr();
        final QueryVisitor visitor = new QueryVisitor();
        expr.visit(visitor);

        processorEntryList = visitor.getProcessorEntryList();
        final Map<TupleExpr, String> idMap = visitor.getIDs();
        // add source node
        builder.addSource(SOURCE, new StringDeserializer(), new VisibilityStatementDeserializer(), statementTopic);

        // processing the processor entry list in reverse order means we go from leaf
        // nodes -> parent nodes.
        // So, when the parent processing nodes get added, the upstream
        // processing node will already exist.

        ProcessorEntry entry = null;
        for (int ii = processorEntryList.size() - 1; ii >= 0; ii--) {
            entry = processorEntryList.get(ii);
            //statement patterns need to be connected to the Source.
            if(entry.getNode() instanceof StatementPattern) {
                builder.addProcessor(entry.getID(), entry.getSupplier(), SOURCE);
            } else {
                final List<TupleExpr> parents = entry.getUpstreamNodes();
                final String[] parentIDs = new String[parents.size()];
                for (int id = 0; id < parents.size(); id++) {
                    parentIDs[id] = idMap.get(parents.get(id));
                }
                builder.addProcessor(entry.getID(), entry.getSupplier(), parentIDs);
            }

            if (entry.getNode() instanceof Join || entry.getNode() instanceof LeftJoin) {
                // Add a state store for the join processor.
                final StateStoreSupplier joinStoreSupplier =
                        Stores.create( entry.getID() )
                        .withStringKeys()
                        .withValues(new VisibilityBindingSetSerde())
                        .persistent()
                        .build();
                builder.addStateStore(joinStoreSupplier, entry.getID());
            }
        }

        // convert processing results to visibility binding sets
        builder.addProcessor("OUTPUT_FORMATTER", new BindingSetOutputFormatterSupplier(), entry.getID());

        // add sink
        builder.addSink(SINK, resultTopic, new StringSerializer(), new VisibilityBindingSetSerializer(), "OUTPUT_FORMATTER");

        return builder;
    }

    @VisibleForTesting
    public List<ProcessorEntry> getProcessorEntry() {
        return processorEntryList;
    }

    /**
     * An entry to be added as a Processing node in kafka streams'
     * TopologyBuilder.
     */
    final static class ProcessorEntry {
        private final TupleExpr node;
        private final String id;
        private final Optional<Side> downstreamSide;
        private final ProcessorSupplier<?, ?> supplier;
        private final List<TupleExpr> upstreamNodes;

        /**
         * Creates a new {@link ProcessorEntry}.
         *
         * @param node - The RDF node to be added as a processor. (not null)
         * @param id - The id for the {@link TupleExpr} node. (not null)
         * @param downstreamSide - Which side the current node is on from its downstream processor. (not null)
         * @param supplier - Supplies the {@link Processor} for this node. (not null)
         * @param upstreamNodes - The RDF nodes that will become upstream processing nodes. (not null)
         */
        public ProcessorEntry(final TupleExpr node, final String id, final Optional<Side> downstreamSide, final ProcessorSupplier<?, ?> supplier, final List<TupleExpr> upstreamNodes) {
            this.node = requireNonNull(node);
            this.id = requireNonNull(id);
            this.downstreamSide = requireNonNull(downstreamSide);
            this.supplier = requireNonNull(supplier);
            this.upstreamNodes = requireNonNull(upstreamNodes);
        }

        /**
         * @return - The RDF node to added as a processor.
         */
        public TupleExpr getNode() {
            return node;
        }

        /**
         * @return - The side the node is on from its downstream processor.
         */
        public Optional<Side> getDownstreamSide() {
            return downstreamSide;
        }

        /**
         * @return - The upstream parents to this node. These parent nodes must
         *         result in a {@link ProcessorEntry}
         */
        public List<TupleExpr> getUpstreamNodes() {
            return upstreamNodes;
        }

        /**
         * @return - The processor id of the node.
         */
        public String getID() {
            return id;
        }

        /**
         * @return - The {@link ProcessorSupplier} used to supply the
         *         {@link Processor} for this node.
         */
        public ProcessorSupplier<?, ?> getSupplier() {
            return supplier;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof ProcessorEntry)) {
                return false;
            }
            final ProcessorEntry o = (ProcessorEntry) other;
            return Objects.equals(node, o.node) &&
                    Objects.equals(id, o.id) &&
                    Objects.equals(downstreamSide, o.downstreamSide) &&
                    Objects.equals(supplier, o.supplier) &&
                    Objects.equals(upstreamNodes, o.upstreamNodes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(node, downstreamSide, upstreamNodes, id, supplier);
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("ID: " + id + "\n");
            if (downstreamSide.isPresent()) {
                sb.append("***********************************\n");
                sb.append("SIDE: " + downstreamSide.get() + "\n");
            }
            sb.append("***********************************\n");
            sb.append("PARENTS: ");
            for (final TupleExpr expr : upstreamNodes) {
                sb.append(expr.toString() + ",");
            }
            sb.append("\n***********************************\n");
            sb.append("NODE: " + node.toString());
            sb.append("\n");
            return sb.toString();
        }
    }

    /**
     * Visits each node in a {@link TupleExpr} and creates a
     * {@link ProcessorSupplier} and meta information needed for creating a
     * {@link TopologyBuilder}.
     */
    final static class QueryVisitor extends QueryModelVisitorBase<TopologyBuilderException> {
        // Each node needs a ProcessorEntry to be a processor node in the
        // TopologyBuilder.
        private final List<ProcessorEntry> entries = new ArrayList<>();
        private final Map<TupleExpr, String> idMap = new HashMap<>();

        /**
         * @return The {@link ProcessorEntry}s used to create a Topology.
         */
        public List<ProcessorEntry> getProcessorEntryList() {
            return entries;
        }

        /**
         * @return The IDs created for each {@link TupleExpr} node in the query that resulted in a {@link ProcessorEntry}.
         */
        public Map<TupleExpr, String> getIDs() {
            return idMap;
        }

        @Override
        public void meet(final StatementPattern node) throws TopologyBuilderException {
            // topology parent for Statement Patterns will always be a source
            final String id = STATEMENT_PATTERN_PREFIX + UUID.randomUUID();
            final Optional<Side> side = getSide(node);
            final StatementPatternProcessorSupplier supplier = new StatementPatternProcessorSupplier(node, result -> getResult(side, result));
            entries.add(new ProcessorEntry(node, id, side, supplier, Lists.newArrayList()));
            idMap.put(node, id);
            super.meet(node);
        }

        @Override
        public void meet(final Projection node) throws TopologyBuilderException {
            final String id = PROJECTION_PREFIX + UUID.randomUUID();
            final Optional<Side> side = getSide(node);
            TupleExpr arg = node.getArg();
            // If the arg is an Extension, there are rebindings that need to be
            // ignored since they do not have a processor node.
            if (arg instanceof Extension) {
                arg = ((Extension) arg).getArg();
            }
            final ProjectionProcessorSupplier supplier = new ProjectionProcessorSupplier(node.getProjectionElemList(), result -> getResult(side, result));
            entries.add(new ProcessorEntry(node, id, side, supplier, Lists.newArrayList(arg)));
            idMap.put(node, id);
            super.meet(node);
        }

        @Override
        public void meet(final Join node) throws TopologyBuilderException {
            final String id = JOIN_PREFIX + UUID.randomUUID();
            meetJoin(id, new NaturalJoin(), node);
            super.meet(node);
        }

        @Override
        public void meet(final LeftJoin node) throws TopologyBuilderException {
            final String id = JOIN_PREFIX + UUID.randomUUID();
            meetJoin(id, new LeftOuterJoin(), node);
            super.meet(node);
        }

        /**
         * Gets the {@link Side} the current node in the visitor is on relative to the provided node.
         * @param node - The node used to determine the side of the current visitor node.
         * @return The {@link Side} the current node is on.
         */
        private Optional<Side> getSide(final QueryModelNode node) {
            // if query parent is a binary operator, need to determine if its left or right.
            if (node.getParentNode() instanceof BinaryTupleOperator) {
                final BinaryTupleOperator binary = (BinaryTupleOperator) node.getParentNode();
                if (node.equals(binary.getLeftArg())) {
                    return Optional.of(Side.LEFT);
                } else {
                    return Optional.of(Side.RIGHT);
                }
            } else {
                return Optional.empty();
            }
        }

        /**
         * Creates a join entry based on a provided {@link IterativeJoin} and the Join's
         * {@link BinaryTupleOperator}.
         *
         * @param id - The ID of the join.
         * @param joinFunction - The {@link IterativeJoin} function to perform during processing.
         * @param node - The {@link BinaryTupleOperator} used to create the process.
         */
        private void meetJoin(final String id, final IterativeJoin joinFunction, final BinaryTupleOperator node) {
            final Set<String> leftArgs = node.getLeftArg().getBindingNames();
            final Set<String> rightArgs = node.getRightArg().getBindingNames();
            final List<String> joinVars = Lists.newArrayList(Sets.intersection(leftArgs, rightArgs));

            leftArgs.removeAll(joinVars);
            rightArgs.removeAll(joinVars);

            final List<String> otherVars = new ArrayList<>();
            otherVars.addAll(leftArgs);
            otherVars.addAll(rightArgs);

            // the join variables need to be sorted so that when compared to all
            // the variables, the start of the all variable list is congruent to
            // the join var list.
            joinVars.sort(Comparator.naturalOrder());
            otherVars.sort(Comparator.naturalOrder());

            final List<String> allVars = new ArrayList<>();
            allVars.addAll(joinVars);
            allVars.addAll(otherVars);

            final Optional<Side> side = getSide(node);
            final JoinProcessorSupplier supplier = new JoinProcessorSupplier(id, joinFunction, joinVars, allVars, result -> getResult(side, result));
            entries.add(new ProcessorEntry(node, id, side, supplier, Lists.newArrayList(node.getLeftArg(), node.getRightArg())));
            idMap.put(node, id);
        }

        /**
         * Creates a {@link ProcessorResult} based on a side and result.
         *
         * @param side - If one is present, a {@link BinaryResult} is created.
         * @param result - The result to wrap in a {@link ProcessorResult}.
         * @return The {@link ProcessorResult} used by the {@link Processor}.
         */
        private ProcessorResult getResult(final Optional<Side> side, final VisibilityBindingSet result) {
            if (side.isPresent()) {
                return ProcessorResult.make(new BinaryResult(side.get(), result));
            } else {
                return ProcessorResult.make(new UnaryResult(result));
            }
        }
    }

    /**
     * An Exception thrown when a problem occurs when constructing the processor
     * topology in the {@link TopologyFactory}.
     */
    public class TopologyBuilderException extends Exception {
        private static final long serialVersionUID = 1L;

        public TopologyBuilderException(final String message, final Throwable cause) {
            super(message, cause);
        }
    }
}