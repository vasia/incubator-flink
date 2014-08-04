package org.apache.flink.fixpoint.examples;

import java.util.Iterator;

import org.apache.flink.api.common.ProgramDescription;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.aggregation.Aggregations;
import org.apache.flink.api.java.functions.FilterFunction;
import org.apache.flink.api.java.functions.GroupReduceFunction;
import org.apache.flink.api.java.functions.MapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.tuple.Tuple4;
import org.apache.flink.api.java.tuple.Tuple5;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.fixpoint.api.FixedPointIteration;
import org.apache.flink.fixpoint.api.StepFunction;
import org.apache.flink.util.Collector;

public class FixpointCommunityDetection implements ProgramDescription {
	
	private static final double delta = 0.5;

	@SuppressWarnings("serial")
	public static void main(String... args) throws Exception {
		
		if (args.length < 4) {
			System.err.println("Parameters: <vertices-path> <edges-path> <result-path> <max_iterations>");
			return;
		}
		ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
		env.setDegreeOfParallelism(1);
		
		DataSet<Tuple2<Long, Tuple2<Long, Double>>> vertices = env.readCsvFile(args[0]).fieldDelimiter('\t').types(Long.class, 
				Long.class, Double.class).map(new MapFunction<Tuple3<Long, Long, Double>, 
						Tuple2<Long, Tuple2<Long, Double>>>(){
					public Tuple2<Long, Tuple2<Long, Double>> map(
							Tuple3<Long, Long, Double> value) throws Exception {
						return new Tuple2<Long, Tuple2<Long, Double>>(value.f0, new Tuple2<Long, Double>(value.f1, value.f2));
					}
					
				});
		
		DataSet<Tuple3<Long, Long, Double>> edges = env.readCsvFile(args[1]).fieldDelimiter('\t').types(Long.class, Long.class, 
				Double.class); 
		
		int maxIterations = Integer.parseInt(args[3]);
	
		DataSet<Tuple2<Long, Tuple2<Long, Double>>> result = vertices.runOperation(FixedPointIteration.withWeightedDependencies(edges, 
				new ComputeCommunities(), maxIterations));

		result.print();
		env.execute("Fixed Point Community Detection");
		
	}
	
	@SuppressWarnings("serial")
	public static final class ComputeCommunities extends StepFunction<Long, Tuple2<Long, Double>, Double> {

		@Override
		public DataSet<Tuple2<Long, Tuple2<Long, Double>>> updateState(
			DataSet<Tuple4<Long, Long, Tuple2<Long, Double>, Double>> inNeighbors) {
			
			// <vertexID, neighborID, neighborLabel, neighborScore, edgeWeight>
			DataSet<Tuple5<Long, Long, Long, Double, Double>> flattenedNeighborWithLabel = inNeighbors.map(new FlattenNeighbors());
			// <vertexID, candidateLabel, labelScore>
			DataSet<Tuple3<Long, Long, Double>> candidateLabels = flattenedNeighborWithLabel.groupBy(0, 2).reduceGroup(new ScoreLabels());
			// <vertexID, newLabel>
			DataSet<Tuple2<Long, Long>> verticesWithNewLabels = candidateLabels.groupBy(0).aggregate(Aggregations.MAX, 2)
					.project(0, 1).types(Long.class, Long.class);
					
			DataSet<Tuple2<Long, Tuple2<Long, Double>>> verticesWithNewScoredLabels = 
					verticesWithNewLabels.join(flattenedNeighborWithLabel).where(0).equalTo(0)
					// <vertexID, newLabel, labelScore>
					.filter(new FilterFunction<Tuple2<Tuple2<Long,Long>,Tuple5<Long,Long,Long,Double,Double>>>() {
						public boolean filter(
								Tuple2<Tuple2<Long, Long>, Tuple5<Long, Long, Long, Double, Double>> value)
								throws Exception {
							return ((value.f0.f1).equals(value.f1.f2));
						}
					})
					.map(new MapFunction<Tuple2<Tuple2<Long,Long>,Tuple5<Long,Long,Long,Double,Double>>, 
							Tuple3<Long, Long, Double>>() {
						public Tuple3<Long, Long, Double> map(
								Tuple2<Tuple2<Long, Long>, Tuple5<Long, Long, Long, Double, Double>> value)
								throws Exception {
							return new Tuple3<Long, Long, Double>(value.f0.f0, value.f0.f1, value.f1.f3);
						}
					})
					.groupBy(0).aggregate(Aggregations.MAX, 2)
					.map(new NewScoreMapper());
			return verticesWithNewScoredLabels;
		}
		
	}
	
	public static final class FlattenNeighbors extends MapFunction<Tuple4<Long, Long, Tuple2<Long, Double>, Double>, 
		Tuple5<Long, Long, Long, Double, Double>> {
		
		private static final long serialVersionUID = 1L;

		@Override
		public Tuple5<Long, Long, Long, Double, Double> map(Tuple4<Long, Long, Tuple2<Long, Double>, Double> value)
				throws Exception {
			return new Tuple5<Long, Long, Long, Double, Double>
				(value.f0, value.f1, value.f2.f0, value.f2.f1, value.f3);
		}
	}
	
	public static final class ScoreLabels extends GroupReduceFunction<Tuple5<Long, Long, Long, Double, Double>, 
		Tuple3<Long, Long, Double>> {

		private static final long serialVersionUID = 1L;
		private double scoreSum; 
		private Tuple5<Long, Long, Long, Double, Double> current;
		private Tuple3<Long, Long, Double> result = new Tuple3<Long, Long, Double>();

		@Override
		public void reduce(
				Iterator<Tuple5<Long, Long, Long, Double, Double>> values,
				Collector<Tuple3<Long, Long, Double>> out) throws Exception {
			
			Tuple5<Long, Long, Long, Double, Double> first = values.next();
			result.setField(first.f0, 0); // vertexID
			result.setField(first.f2, 1); // label
			scoreSum = first.f3 * first.f4; // score * edgeWeight
			
			while (values.hasNext()) {
				current = values.next();
				// TODO: multiply with degree(vertex)^m
				scoreSum += current.f3 * current.f4;
			}
			System.out.println("Group ID: " + result.f0 + ", label: " + result.f1 );
			result.setField(scoreSum, 2);
			out.collect(result);
		}
		
	}
	
	public static final class FlattenState extends MapFunction<Tuple2<Long, Tuple2<Long, Double>>, 
		Tuple3<Long, Long, Double>> {
	
		private static final long serialVersionUID = 1L;

		@Override
		public Tuple3<Long, Long, Double> map(Tuple2<Long, Tuple2<Long, Double>> value)
				throws Exception {
			return new Tuple3<Long, Long, Double>
				(value.f0, value.f1.f0, value.f1.f1);
		}
	}
	
	public static final class NewScoreMapper extends MapFunction<Tuple3<Long, Long, Double>, 
		Tuple2<Long, Tuple2<Long, Double>>>{
		
		private static final long serialVersionUID = 1L;
		private int superstep;
		
		@Override
		public void open(Configuration conf){
			superstep = getIterationRuntimeContext().getSuperstepNumber();
		}
		
		@Override
		public Tuple2<Long, Tuple2<Long, Double>> map(Tuple3<Long, Long, Double> value)
				throws Exception {
			System.out.println("VID:" + value.f0 + ", label: " + value.f1 + ", score: " +value.f2);
			return new Tuple2<Long, Tuple2<Long, Double>>(
					value.f0, new Tuple2<Long, Double>(value.f1, value.f2 - (delta / (double) superstep)));
		}
		
	}
		
	@Override
	public String getDescription() {
		return "Parameters: <vertices-path> <edges-path> <result-path> <max-number-of-iterations>";
	}

}
