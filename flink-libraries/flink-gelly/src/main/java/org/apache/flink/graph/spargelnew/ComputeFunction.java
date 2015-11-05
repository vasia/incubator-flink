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

package org.apache.flink.graph.spargelnew;

import java.io.Serializable;
import java.util.Collection;
import java.util.Iterator;

import org.apache.flink.api.common.aggregators.Aggregator;
import org.apache.flink.api.common.functions.IterationRuntimeContext;
import org.apache.flink.api.java.tuple.Tuple;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.graph.Edge;
import org.apache.flink.graph.EdgeDirection;
import org.apache.flink.graph.Vertex;
import org.apache.flink.types.Value;
import org.apache.flink.util.Collector;

/**
 * The base class for the message-passing functions between vertices as a part of a {@link MessagePassingteration}.
 * 
 * @param <K> The type of the vertex key (the vertex identifier).
 * @param <VV> The type of the vertex value (the state of the vertex).
 * @param <EV> The type of the values that are associated with the edges.
 * @param <Message> The type of the message sent between vertices along the edges.
 */
public abstract class ComputeFunction<K, VV, EV, Message> implements Serializable {

	private static final long serialVersionUID = 1L;

	// --------------------------------------------------------------------------------------------
	//  Public API Methods
	// --------------------------------------------------------------------------------------------

	/**
	 * This method is invoked once per superstep, for each active vertex.
	 * A vertex is active during a superstep, if at least one message was produced for it,
	 * in the previous superstep. During the first superstep, all vertices are active.
	 * <p>
	 * This method can iterate over all received messages, set the new vertex value, and
	 * send messages to other vertices (which will be delivered in the next superstep).
	 * 
	 * @param vertex The vertex executing this function
	 * @param messages The messages that were sent to this vertex in the previous superstep
	 * @throws Exception
	 */
	public abstract void compute(Vertex<K, VV> vertex, MessageIterator<Message> messages) throws Exception;

	/**
	 * This method is executed one per superstep before the vertex update function is invoked for each vertex.
	 * 
	 * @throws Exception Exceptions in the pre-superstep phase cause the superstep to fail.
	 */
	public void preSuperstep() throws Exception {}
	
	/**
	 * This method is executed one per superstep after the vertex update function has been invoked for each vertex.
	 * 
	 * @throws Exception Exceptions in the post-superstep phase cause the superstep to fail.
	 */
	public void postSuperstep() throws Exception {}
	
	
	/**
	 * Gets an {@link java.lang.Iterable} with all edges. This method is mutually exclusive with
	 * {@link #sendMessageToAllNeighbors(Object)} and may be called only once.
	 * <p>
	 * If the {@link EdgeDirection} is OUT (default), then this iterator contains outgoing edges.
	 * If the {@link EdgeDirection} is IN, then this iterator contains incoming edges.
	 * If the {@link EdgeDirection} is ALL, then this iterator contains both outgoing and incoming edges.
	 * 
	 * @return An iterator with all edges.
	 */
	@SuppressWarnings("unchecked")
	public final Iterable<Edge<K, EV>> getEdges() {
		if (edgesUsed) {
			throw new IllegalStateException("Can use either 'getEdges()' or 'sendMessageToAllTargets()' exactly once.");
		}
		edgesUsed = true;
		this.edgeIterator.set((Iterator<Edge<K, EV>>) edges);
		return this.edgeIterator;
	}

	/**
	 * Sends the given message to all vertices that are targets of an edge of the changed vertex.
	 * This method is mutually exclusive to the method {@link #getEdges()} and may be called only once.
	 * 
	 * @param m The message to send.
	 */
	public final void sendMessageToAllNeighbors(Message m) {
		if (edgesUsed) {
			throw new IllegalStateException("Can use either 'getEdges()' or 'sendMessageToAllNeighbors()'"
					+ "exactly once.");
		}
		
		edgesUsed = true;

		outValue.setField(MESSAGE, 2);

		outValue.f1.setField(m, 1);
		
		while (edges.hasNext()) {
			Tuple next = (Tuple) edges.next();
			outValue.f1.setField(next.getField(1), 0);
			out.collect(outValue);
		}
	}
	
	/**
	 * Sends the given message to the vertex identified by the given key. If the target vertex does not exist,
	 * the next superstep will cause an exception due to a non-deliverable message.
	 * 
	 * @param target The key (id) of the target vertex to message.
	 * @param m The message.
	 */
	public final void sendMessageTo(K target, Message m) {

		outValue.setField(MESSAGE, 2);

		outValue.f1.setField(target, 0);
		outValue.f1.setField(m, 1);

		out.collect(outValue);
	}

	/**
	 * Sets the new value of this vertex.
	 *
	 * This should be called at most once per ComputeFunction.
	 * 
	 * @param newValue The new vertex value.
	 */
	public final void setNewVertexValue(VV newValue) {
		if(setNewVertexValueCalled) {
			throw new IllegalStateException("setNewVertexValue should only be called at most once per updateVertex");
		}
		setNewVertexValueCalled = true;

		outValue.setField(VERTEXVALUE, 2);
		outValue.f0.setField(newValue, 1);

		out.collect(outValue);
	}
	// --------------------------------------------------------------------------------------------
	
	/**
	 * Gets the number of the superstep, starting at <tt>1</tt>.
	 * 
	 * @return The number of the current superstep.
	 */
	public final int getSuperstepNumber() {
		return this.runtimeContext.getSuperstepNumber();
	}
	
	/**
	 * Gets the iteration aggregator registered under the given name. The iteration aggregator combines
	 * all aggregates globally once per superstep and makes them available in the next superstep.
	 * 
	 * @param name The name of the aggregator.
	 * @return The aggregator registered under this name, or null, if no aggregator was registered.
	 */
	public final <T extends Aggregator<?>> T getIterationAggregator(String name) {
		return this.runtimeContext.<T>getIterationAggregator(name);
	}
	
	/**
	 * Get the aggregated value that an aggregator computed in the previous iteration.
	 * 
	 * @param name The name of the aggregator.
	 * @return The aggregated value of the previous iteration.
	 */
	public final <T extends Value> T getPreviousIterationAggregate(String name) {
		return this.runtimeContext.<T>getPreviousIterationAggregate(name);
	}
	
	/**
	 * Gets the broadcast data set registered under the given name. Broadcast data sets
	 * are available on all parallel instances of a function. They can be registered via
	 * {@link org.apache.flink.graph.spargel.VertexCentricConfiguration#addBroadcastSetForMessagingFunction(String, org.apache.flink.api.java.DataSet)}.
	 * 
	 * @param name The name under which the broadcast set is registered.
	 * @return The broadcast data set.
	 */
	public final <T> Collection<T> getBroadcastSet(String name) {
		return this.runtimeContext.<T>getBroadcastVariable(name);
	}

	// --------------------------------------------------------------------------------------------
	//  internal methods and state
	// --------------------------------------------------------------------------------------------

	private Tuple3<Vertex<K, VV>, Tuple2<K, Message>, Boolean> outValue;

	private static final boolean MESSAGE = true;

	private static final boolean VERTEXVALUE = false;
	
	private IterationRuntimeContext runtimeContext;
	
	private Iterator<?> edges;
	
	private Collector<Tuple3<Vertex<K, VV>, Tuple2<K, Message>, Boolean>> out;
	
	private EdgesIterator<K, EV> edgeIterator;
	
	private boolean edgesUsed;

	private boolean setNewVertexValueCalled;
	
	void init(IterationRuntimeContext context) {
		this.runtimeContext = context;
		this.outValue = new Tuple3<Vertex<K, VV>, Tuple2<K, Message>, Boolean>();
		this.outValue.setField(new Vertex<K, VV>(), 0);
		this.outValue.setField(new Tuple2<K, Message>(), 1);
		this.edgeIterator = new EdgesIterator<K, EV>();
	}
	
	void set(Vertex<K, VV> v, Message dummy, Iterator<?> edges,
			Collector<Tuple3<Vertex<K, VV>, Tuple2<K, Message>, Boolean>> out) {

		this.outValue.setField(v, 0);
		this.outValue.setField(new Tuple2<K, Message>(v.getId(), dummy), 1);
		this.edges = edges;
		this.out = out;
		this.edgesUsed = false;
		setNewVertexValueCalled = false;
	}
	
	private static final class EdgesIterator<K, EV> 
		implements Iterator<Edge<K, EV>>, Iterable<Edge<K, EV>>
	{
		private Iterator<Edge<K, EV>> input;
		
		private Edge<K, EV> edge = new Edge<K, EV>();
		
		void set(Iterator<Edge<K, EV>> input) {
			this.input = input;
		}
		
		@Override
		public boolean hasNext() {
			return input.hasNext();
		}

		@Override
		public Edge<K, EV> next() {
			Edge<K, EV> next = input.next();
			edge.setSource(next.f0);
			edge.setTarget(next.f1);
			edge.setValue(next.f2);
			return edge;
		}

		@Override
		public void remove() {
			throw new UnsupportedOperationException();
		}
		@Override
		public Iterator<Edge<K, EV>> iterator() {
			return this;
		}
	}
}
