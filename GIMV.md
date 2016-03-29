# Introduction #

Generalized Iterated Matrix-Vector multiplication (GIM-V)  is an abstraction of many iterative graph mining operations (PageRank, spectral clustering, diameter estimation, connected components etc.). Suppose we have an n\*n matrix M and a vector v of size n. Let m_{i,j} denote the (i,j)-th element of M. These graph mining algorithms can be generally defined through three operations: 1) combine2(m_{i,j},v\_j), 2) combineAll\_i(x\_1,...,x\_n), and 3) assign(v\_j,v_{new}). To implement GIM-V in MapReduce, the original paper uses two jobs in each iteration. One job is used to join the structure data M (M is iteration-invariant) and the state data v (vector v is updated in each iteration). However, in i2MapReduce, thanks to the `project` function, we only need one map-reduce phase in each iteration. The combine2(m_{i,j},v\_j) function requires a matrix element m_{i,j} (structure record) and a vector element v\_j (state record). Many matrix elements \forall i, m_{i,j} are projected to the jth vector element v\_j. So, the correlation between structure kv and state kv is **many-to-one**.

# Details #

  * [GIM-V implementation](WikiSyntax#Iterative.md)

  * [WikiSyntax#Incremental\_GIM-V\_implementation](WikiSyntax#Incremental_GIM-V_implementation.md)


### Iterative GIM-V implementation ###


```
package org.apache.hadoop.examples.iterative;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

import org.apache.hadoop.examples.utils.PairWritable;
import org.apache.hadoop.examples.utils.Parameters;
import org.apache.hadoop.examples.utils.Util;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.IterativeMapper;
import org.apache.hadoop.mapred.IterativeReducer;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Partitioner;
import org.apache.hadoop.mapred.Projector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.TextInputFormat;

public class IterGIMV {
	
	public static final int VECTORMARK = -1;
	public static final int SELF = -2;
	public static final int OTHERS = -3;
	
	//matrix split by blocks
	public static class MatrixBlockingMapper extends MapReduceBase
		implements Mapper<LongWritable, Text, PairWritable, Text> {

		private int rowBlockSize;
		private int colBlockSize;
		
		@Override
		public void configure(JobConf job){
			rowBlockSize = job.getInt("matrixvector.row.blocksize", 0);
			colBlockSize = job.getInt("matrixvector.col.blocksize", 0);
		}
		
		public void map(LongWritable key, Text value,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			String line = value.toString();

			//matrix
			String[] field = line.split(" ", 3);
			if(field.length != 3) throw new IOException("input not in correct format, should be 3");
			
			int row = Integer.parseInt(field[0]);
			int column = Integer.parseInt(field[1]);
			double v = Double.parseDouble(field[2]);
			
			int rowBlockIndex = row / rowBlockSize;
			int colBlockIndex = column / colBlockSize;
			
			System.out.println("output: " + rowBlockIndex + "," + colBlockIndex + "\t" + row + "," + column + "," + v);
			
			output.collect(new PairWritable(rowBlockIndex, colBlockIndex), new Text(row + "," + column + "," + v));
		}
	}

	public static class MatrixBlockingReducer extends MapReduceBase
		implements Reducer<PairWritable, Text, PairWritable, Text> {
		@Override
		public void reduce(PairWritable key, Iterator<Text> values,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			String outputv = "";
			
			while(values.hasNext()){
				String value = values.next().toString();
				
				System.out.println("input: " + key + "\t" + value);
				
				outputv += value + " ";
			}
			
			System.out.println("output: " + key + "\t" + outputv);
			
			output.collect(key, new Text(outputv));
		}
	}

	/**
	 * GIM-V's interface
	 * combine2()
	 * combineAll()
	 * assign()
	 */
	//GIM-V combine2() interface
	private static String combine2(Map<Integer, TreeMap<Integer, Double>> matrixBlock,
			Map<Integer, Double> vectorBlock){
		String out = "";
		for(Map.Entry<Integer, TreeMap<Integer, Double>> entry : matrixBlock.entrySet()){
			int row = entry.getKey();
			double rowv = 0;
			for(Map.Entry<Integer, Double> entry2 : entry.getValue().entrySet()){
				if(!vectorBlock.containsKey(entry2.getKey())) continue;
				//System.out.println("value " + entry2.getValue() + "\t vector key " + entry2.getKey() + "\t" + vectorBlock.size());
				rowv += entry2.getValue() * vectorBlock.get(entry2.getKey());
			}
			out += row + "," + rowv + " ";
		}
		return out;
	}
	
	//GIM-V combineAll() interface
	private static Map<Integer, Double> combineAll(Map<Integer, ArrayList<Double>> vectors){
		Map<Integer, Double> vector = new TreeMap<Integer, Double>();
		for(Map.Entry<Integer, ArrayList<Double>> entry : vectors.entrySet()){
			double sum = 0;
			for(double value : entry.getValue()){
				sum += value;
			}
			vector.put(entry.getKey(), sum);
		}
		return vector;
	}
	
	//GIM-V assign() interface
	private static String assign(Map<Integer, Double> oldV, Map<Integer, Double> newV){
		String out = "";
		for(Map.Entry<Integer, Double> entry : newV.entrySet()){
			int row = entry.getKey();
			double rowv = entry.getValue();
			out += row + "," + rowv + " ";
		}
		return out;
	}
	
	/**
	 * matrixvector1 mapper and reducer
	 */
	public static class GIMVMap extends MapReduceBase
		implements IterativeMapper<PairWritable, Text, PairWritable, Text, PairWritable, Text> {

		private int mapcount = 0;
		private int bufferedVectorINdex= -100;
		
		@Override
		public void map(PairWritable statickey, Text staticval,
				PairWritable dynamickey, Text dynamicvalue,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			mapcount++;
			reporter.setStatus(String.valueOf(mapcount));
			
			Map<Integer, TreeMap<Integer, Double>> matrixBlock = new TreeMap<Integer, TreeMap<Integer, Double>>();
			Map<Integer, Double> vectorBlock = new HashMap<Integer, Double>();
			
			String matrixline = staticval.toString();
			
			//System.out.println(statickey + "\t" + matrixline);
			
			StringTokenizer st = new StringTokenizer(matrixline);
			while(st.hasMoreTokens()){
				String[] field = st.nextToken().split(",");
				if(field.length == 3){
					//matrix block
					int rowIndex = Integer.parseInt(field[0]);
					int colIndex = Integer.parseInt(field[1]);
					double v = Double.parseDouble(field[2]);

					if(!matrixBlock.containsKey(rowIndex)){
						TreeMap<Integer, Double> row = new TreeMap<Integer, Double>();
						matrixBlock.put(rowIndex, row);
					}
					
					matrixBlock.get(rowIndex).put(colIndex, v);
				}else{
					throw new IOException("impossible!!");
				}
			}
			
			String vectorline = dynamicvalue.toString();
			
			//System.out.println(dynamickey + "\t" + vectorline);
			
			StringTokenizer st2 = new StringTokenizer(vectorline);
			while(st2.hasMoreTokens()){
				String[] field = st2.nextToken().split(",");
				if(field.length == 2){
					//vector block
					vectorBlock.put(Integer.parseInt(field[0]), Double.parseDouble(field[1]));
					//System.out.println("put " + field[0] + "\t" + field[1]);
				}else{
					throw new IOException("impossible!!");
				}
			}
			
			/**
			 * there are many repeated dynamic keys, we only output the unique key,
			 * because: each dynamic key (vector entry) corresponds to many static key (matrix block)
			 * so we have a buffer for unique key, only when the input key not equal to the buffer key
			 * we output the key value,
			 */
			if(dynamickey.getX() != bufferedVectorINdex){
				output.collect(new PairWritable(dynamickey.getX(), VECTORMARK), new Text(SELF + ":" + vectorline));
				//System.out.println("output: " + new PairWritable(dynamickey.getX(), VECTORMARK) + "\t" + SELF + ":" + vectorline);
				
				bufferedVectorINdex = dynamickey.getX();
			}

			
			String out = combine2(matrixBlock, vectorBlock);
			output.collect(new PairWritable(statickey.getX(), VECTORMARK), new Text(OTHERS + ":" + out));
			//System.out.println("output: " + new PairWritable(statickey.getX(), VECTORMARK) + "\t" + OTHERS + ":" + out);
		}

		@Override
		public Text removeLable() {
			// TODO Auto-generated method stub
			return null;
		}
	}
	
	public static class GIMVReduce extends MapReduceBase
		implements IterativeReducer<PairWritable, Text, PairWritable, Text> {

		private int redcount = 0;
		private long iter_start;
		private long last_iter_end;
		
		@Override
		public void configure(JobConf job){
			iter_start = job.getLong(Parameters.ITER_START, 0);
			last_iter_end = iter_start;
		}
		
		@Override
		public void reduce(PairWritable key, Iterator<Text> values,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			redcount++;
			reporter.setStatus(String.valueOf(redcount));
			
			Map<Integer, ArrayList<Double>> vectorBlock = new HashMap<Integer, ArrayList<Double>>();
			Map<Integer, Double> oldVector = new HashMap<Integer, Double>();
			
			while(values.hasNext()){
				String valueline = values.next().toString();
				
				//System.out.println(key + "\t" + valueline);
				
				int index = valueline.indexOf(":");
				int mark = Integer.parseInt(valueline.substring(0, index));
				String value = valueline.substring(index+1);
				if(mark == SELF){
					StringTokenizer st = new StringTokenizer(value);
					while(st.hasMoreTokens()){
						String entry = st.nextToken();
						String[] field = entry.split(",");
						int row = Integer.parseInt(field[0]);
						double v = Double.parseDouble(field[1]);
						oldVector.put(row, v);
					}
				}else if(mark == OTHERS){
					StringTokenizer st = new StringTokenizer(value);
					while(st.hasMoreTokens()){
						String entry = st.nextToken();
						String[] field = entry.split(",");
						int row = Integer.parseInt(field[0]);
						double v = Double.parseDouble(field[1]);
						
						if(!vectorBlock.containsKey(row)){
							ArrayList<Double> vectorlist = new ArrayList<Double>();
							vectorBlock.put(row, vectorlist);
						}
						
						vectorBlock.get(row).add(v);
					}
				}
			}
	
			Map<Integer, Double> newVector = combineAll(vectorBlock);
			String out = assign(oldVector, newVector);
			
			output.collect(key, new Text(out));		
			//System.out.println("output: " + key + "\t" + out);		
		}

		// use manhaten distance
		@Override
		public float distance(PairWritable key, Text prevV, Text currV)
				throws IOException {

			double change = 0;
			Map<Integer, Double> vectorBlock = new TreeMap<Integer, Double>();
			
			StringTokenizer st = new StringTokenizer(prevV.toString());
			while(st.hasMoreTokens()){
				String entry = st.nextToken();
				String[] field = entry.split(",");
				int row = Integer.parseInt(field[0]);
				double v = Double.parseDouble(field[1]);
				
				vectorBlock.put(row, v);
			}
			
			st = new StringTokenizer(currV.toString());
			while(st.hasMoreTokens()){
				String entry = st.nextToken();
				String[] field = entry.split(",");
				int row = Integer.parseInt(field[0]);
				double v = Double.parseDouble(field[1]);
				
				change += Math.abs(v - vectorBlock.get(row));
			}
			
			return (float)change;
		}

		@Override
		public Text removeLable() {
			// TODO Auto-generated method stub
			return null;
		}


		@Override
		public void iteration_complete(int iteration) {
			long curr_time = System.currentTimeMillis();
			System.out.println("iteration " + iteration + " takes " + 
					(curr_time-last_iter_end) + " total " + (curr_time-iter_start));
			last_iter_end = curr_time;
		}
	}

	public static class GIMVProjector implements Projector<PairWritable, PairWritable, Text> {

		private int rowBlockSize;
		
		@Override
		public void configure(JobConf job){
			rowBlockSize = job.getInt("matrixvector.row.blocksize", 0);
		}

		@Override
		public PairWritable project(PairWritable statickey) {
			return new PairWritable(statickey.getY(), VECTORMARK);
		}

		@Override
		public Text initDynamicV(PairWritable dynamickey) {
			int index = dynamickey.getX() * rowBlockSize;
			String out = "";
			for(int i=index; i<index+rowBlockSize; i++){
				out += i + ",0.00001 ";
			}
			return new Text(out);
		}

		/**
		 * set the dynamic key's partitioner, we use it:
		 * 1. static data distribution job: to partition the static data based on this partitioner
		 * 2. iterative processing job: to partition the dynamic data
		 */
		@Override
		public Partitioner<PairWritable, Text> getDynamicKeyPartitioner() {
			return new MatrixVectorPartitioner();
		}

		@Override
		public org.apache.hadoop.mapred.Projector.Type getProjectType() {
			return Projector.Type.ONE2MUL;
		}
	}
	
	public static class MatrixVectorPartitioner implements Partitioner<PairWritable, Text> {
		@Override
		public int getPartition(PairWritable key, Text value, int numPartitions) {
			return key.getX() % numPartitions;
		}

		@Override
		public void configure(JobConf job) {}
	}

	  
	private static void printUsage() {
		System.out.println("itergimv <inStaticDir> <outDir>");
		System.out.println(	"\t-rb row block size\n" +
							"\t-cb column block size\n" +
							"\t-p # of parittions\n" +
							"\t-i snapshot interval\n" +
							"\t-I # of iterations\n" +
							"\t-s run preserve job");
	}
	
	/**
	 * @param args
	 * @throws IOException 
	 */
	public static int main(String[] args) throws IOException {
		if (args.length < 2) {
			return -1;
		}
		
		int partitions = 0;
		int interval = 1;
		int max_iterations = 10;
		int rowBlockSize = 0;
		int colBlockSize = 0;
		boolean preserve = false;
		
		List<String> other_args = new ArrayList<String>();
		for(int i=0; i < args.length; ++i) {
		      try {
		          if ("-rb".equals(args[i])) {
		        	  rowBlockSize = Integer.parseInt(args[++i]);
		          } else if ("-cb".equals(args[i])) {
		        	  colBlockSize = Integer.parseInt(args[++i]);
		          } else if ("-I".equals(args[i])) {
		        	  max_iterations = Integer.parseInt(args[++i]);
		          } else if ("-i".equals(args[i])) {
		        	  interval = Integer.parseInt(args[++i]);
		          } else if ("-p".equals(args[i])) {
		        	  partitions = Integer.parseInt(args[++i]);
		          } else if ("-s".equals(args[i])) {
		        	  preserve = Boolean.parseBoolean(args[++i]);
		          } else {
		    		  other_args.add(args[i]);
		    	  }
		      } catch (NumberFormatException except) {
		        System.out.println("ERROR: Integer expected instead of " + args[i]);
		        printUsage();
		        return -1;
		      } catch (ArrayIndexOutOfBoundsException except) {
		        System.out.println("ERROR: Required parameter missing from " +
		                           args[i-1]);
		        printUsage();
		        return -1;
		      }
		}
		
	    if (other_args.size() < 2) {
		      System.out.println("ERROR: Wrong number of parameters: " +
		                         other_args.size() + ".");
		      printUsage(); return -1;
		}
		
	    String inStatic = other_args.get(0);
	    String output = other_args.get(1);
		
		long initstart = System.currentTimeMillis();

		String iteration_id = "GIM-V " + new Date().getTime();
		
		/**
		 * job to block the input data
		 */
		JobConf job1 = new JobConf(IterGIMV.class);
		job1.setJobName("GIM-V Blocking");
		job1.setDataDistribution(true);
		job1.setIterativeAlgorithmID(iteration_id);
		
		job1.setOutputKeyClass(PairWritable.class);
		job1.setOutputValueClass(Text.class);
		job1.setMapperClass(MatrixBlockingMapper.class);
		job1.setReducerClass(MatrixBlockingReducer.class);
		job1.setInputFormat(TextInputFormat.class);
		job1.setOutputFormat(SequenceFileOutputFormat.class);
		
		FileInputFormat.setInputPaths(job1, new Path(inStatic));
		FileOutputFormat.setOutputPath(job1, new Path(output + "/substatic"));
		
		job1.setInt("matrixvector.row.blocksize", rowBlockSize);
		job1.setInt("matrixvector.col.blocksize", colBlockSize);
		
		job1.setProjectorClass(GIMVProjector.class);
		
		job1.setNumReduceTasks(partitions);
		
		JobClient.runJob(job1);

		long initend = System.currentTimeMillis();
		Util.writeLog("iter.gimv.log", "init job use " + (initend - initstart)/1000 + " s");
		
	    /**
	     * start iterative application jobs
	     */
	    long itertime = 0;
	    
		/****************** Main Job ********************************/
		long iterstart = System.currentTimeMillis();;
		JobConf job2 = new JobConf(IterGIMV.class);
		job2.setJobName("Iter GIM-V");

		job2.setIterative(true);
		job2.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
		job2.setLong(Parameters.ITER_START, iterstart);
		
	    if(max_iterations == Integer.MAX_VALUE){
	    	job2.setDistanceThreshold(1);
	    }else{
	    	job2.setMaxIterations(max_iterations);
	    }
	    
	    //init dynamic data in projector() interface
	    job2.setInitWithFileOrApp(false);
	    
		job2.setCheckPointInterval(interval);					//checkpoint interval
		
		job2.setStaticDataPath(output + "/substatic");
		job2.setDynamicDataPath(output + "/result");	
		job2.setStaticInputFormat(SequenceFileInputFormat.class);
		job2.setDynamicInputFormat(SequenceFileInputFormat.class);		//MUST have this for the following jobs, even though the first job not need it
		job2.setResultInputFormat(SequenceFileInputFormat.class);		//if set termination check, you have to set this
		job2.setOutputFormat(SequenceFileOutputFormat.class);

		FileInputFormat.addInputPaths(job2, output + "/substatic");
		FileOutputFormat.setOutputPath(job2, new Path(output + "/result"));

	    job2.setOutputKeyClass(PairWritable.class);
	    job2.setOutputValueClass(Text.class);
	    
	    job2.setInt("matrixvector.row.blocksize", rowBlockSize);
	    
	    job2.setIterativeMapperClass(GIMVMap.class);	
	    job2.setIterativeReducerClass(GIMVReduce.class);
	    job2.setProjectorClass(GIMVProjector.class);
	    job2.setPartitionerClass(MatrixVectorPartitioner.class);
	    
	    job2.setNumReduceTasks(partitions);

		JobClient.runIterativeJob(job2);
		
    	long iterend = System.currentTimeMillis();
    	itertime += (iterend - iterstart) / 1000;
    	Util.writeLog("iter.gimv.log", "iterative computation takes " + itertime + " s");
		
	    if(preserve){
		    //preserving job
	    	long preservestart = System.currentTimeMillis();
	    	
		    JobConf job3 = new JobConf(IterGIMV.class);
		    job3.setJobName("GIMV Preserve ");
	    
		    if(partitions == 0) partitions = Util.getTTNum(job3);
		    
		    //set for iterative process   
		    job3.setPreserve(true);
		    job3.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
		    //job2.setIterationNum(iteration);					//iteration numbe
		    job3.setCheckPointInterval(interval);					//checkpoint interval
		    job3.setStaticDataPath(output + "/substatic");
		    job3.setDynamicDataPath(output + "/result/iteration-" + max_iterations);	
		    job3.setStaticInputFormat(SequenceFileInputFormat.class);
		    job3.setDynamicInputFormat(SequenceFileInputFormat.class);		//MUST have this for the following jobs, even though the first job not need it
		    job3.setResultInputFormat(SequenceFileInputFormat.class);		//if set termination check, you have to set this
		    job3.setOutputFormat(SequenceFileOutputFormat.class);
		    job3.setPreserveStatePath(output + "/preserve");
		    
		    FileInputFormat.addInputPath(job3, new Path(output + "/substatic"));
		    FileOutputFormat.setOutputPath(job3, new Path(output + "/preserve/convergeState"));
		    
		    if(max_iterations == Integer.MAX_VALUE){
		    	job3.setDistanceThreshold(1);
		    }

		    job3.setStaticKeyClass(PairWritable.class);
		    job3.setOutputKeyClass(PairWritable.class);
		    job3.setOutputValueClass(Text.class);
		    
		    job3.setIterativeMapperClass(GIMVMap.class);	
		    job3.setIterativeReducerClass(GIMVReduce.class);
		    job3.setProjectorClass(GIMVProjector.class);
		    job3.setPartitionerClass(MatrixVectorPartitioner.class);
		    
		    job3.setNumReduceTasks(partitions);			

		    JobClient.runIterativeJob(job3);

	    	long preserveend = System.currentTimeMillis();
	    	long preservationtime = (preserveend - preservestart) / 1000;
	    	Util.writeLog("iter.gimv.log", "iteration preservation takes " + preservationtime + " s");
	    }
    	
		return 0;
    }
}

```

### Incremental GIM-V implementation ###

```
package org.apache.hadoop.examples.incremental;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

import org.apache.hadoop.examples.utils.PairWritable;
import org.apache.hadoop.examples.utils.Parameters;
import org.apache.hadoop.examples.utils.Util;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.IFile;
import org.apache.hadoop.mapred.IterativeMapper;
import org.apache.hadoop.mapred.IterativeReducer;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Partitioner;
import org.apache.hadoop.mapred.Projector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.TextInputFormat;

public class IncrGIMV {
	
	public static final int VECTORMARK = -1;
	public static final int SELF = -2;
	public static final int OTHERS = -3;
	
	//matrix split by blocks
	public static class MatrixBlockingMapper extends MapReduceBase
		implements Mapper<LongWritable, Text, PairWritable, Text> {

		private int rowBlockSize;
		private int colBlockSize;
		
		@Override
		public void configure(JobConf job){
			rowBlockSize = job.getInt("matrixvector.row.blocksize", 0);
			colBlockSize = job.getInt("matrixvector.col.blocksize", 0);
		}
		
		public void map(LongWritable key, Text value,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			String line = value.toString();

			//matrix
			String[] field = line.split(" ", 3);
			if(field.length != 3) throw new IOException("input not in correct format, should be 3");
			
			int row = Integer.parseInt(field[0]);
			int column = Integer.parseInt(field[1]);
			double v = Double.parseDouble(field[2]);
			
			int rowBlockIndex = row / rowBlockSize;
			int colBlockIndex = column / colBlockSize;
			
			System.out.println("output: " + rowBlockIndex + "," + colBlockIndex + "\t" + row + "," + column + "," + v);
			
			output.collect(new PairWritable(rowBlockIndex, colBlockIndex), new Text(row + "," + column + "," + v));
		}
	}
	
	public static class MatrixBlockingReducer extends MapReduceBase
		implements Reducer<PairWritable, Text, PairWritable, Text> {
		@Override
		public void reduce(PairWritable key, Iterator<Text> values,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			String outputv = "";
			
			while(values.hasNext()){
				String value = values.next().toString();
				
				System.out.println("input: " + key + "\t" + value);
				
				outputv += value + " ";
			}
			
			System.out.println("output: " + key + "\t" + outputv);
			
			output.collect(key, new Text(outputv));
		}
	}
	
	//matrix split by blocks
	public static class MatrixBlockingMapper2 extends MapReduceBase
		implements Mapper<LongWritable, Text, PairWritable, Text> {

		private int rowBlockSize;
		private int colBlockSize;
		
		@Override
		public void configure(JobConf job){
			rowBlockSize = job.getInt("matrixvector.row.blocksize", 0);
			colBlockSize = job.getInt("matrixvector.col.blocksize", 0);
		}
		
		public void map(LongWritable key, Text value,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			String line = value.toString();

			//matrix
			String[] field = line.split(" ");
			
			int row = Integer.parseInt(field[0]);
			int column = Integer.parseInt(field[1]);
			double v = Double.parseDouble(field[2]);
			
			int rowBlockIndex = row / rowBlockSize;
			int colBlockIndex = column / colBlockSize;
			
			if(field.length == 4){
				String changetype = field[3];
				System.out.println("output: " + rowBlockIndex + "," + colBlockIndex + "\t" + row + "," + column + "," + v + "\t" + changetype);
				output.collect(new PairWritable(rowBlockIndex, colBlockIndex), new Text(row + "," + column + "," + v + "\t" + changetype));
			}else{
				System.out.println("output: " + rowBlockIndex + "," + colBlockIndex + "\t" + row + "," + column + "," + v);
				output.collect(new PairWritable(rowBlockIndex, colBlockIndex), new Text(row + "," + column + "," + v));
			}
		}
	}
	
	public static class MatrixBlockingReducer2 extends MapReduceBase
		implements Reducer<PairWritable, Text, PairWritable, Text> {
		
		private IFile.TrippleWriter<PairWritable, Text, Text> writer;
		
		@Override
		public void configure(JobConf job){
			FileSystem fs;
			try {
				fs = FileSystem.get(job);
				Path deltapath = new Path(job.get("gimv.delta.update.path") + "/part-" + Util.getTaskId(job));
				writer = new IFile.TrippleWriter<PairWritable, Text, Text>(job, fs, deltapath, 
						PairWritable.class, Text.class, Text.class, null, null);
			} catch (IOException e) {
				e.printStackTrace();
			}
		}
		
		@Override
		public void reduce(PairWritable key, Iterator<Text> values,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			String outputv = "";
			
			boolean containchange = false;
			while(values.hasNext()){
				String value = values.next().toString();
				System.out.println("input: " + key + "\t" + value);
				
				//there is a change
				if(value.indexOf("\t") != -1){
					containchange = true;
					outputv += value.substring(0, value.indexOf("\t")) + " ";
				}else{
					outputv += value + " ";
				}
			}
			
			if(containchange){
				writer.append(key, new Text(outputv), new Text("+"));
				System.out.println("output: " + key + "\t" + outputv + "\t+");
			}
		}
		
		@Override
		public void close() throws IOException{
			writer.close();
		}
	}
	
	/**
	 * GIM-V's interface
	 * combine2()
	 * combineAll()
	 * assign()
	 */
	//GIM-V combine2() interface
	private static String combine2(Map<Integer, TreeMap<Integer, Double>> matrixBlock,
			Map<Integer, Double> vectorBlock){
		String out = "";
		for(Map.Entry<Integer, TreeMap<Integer, Double>> entry : matrixBlock.entrySet()){
			int row = entry.getKey();
			double rowv = 0;
			for(Map.Entry<Integer, Double> entry2 : entry.getValue().entrySet()){
				//System.out.println("value " + entry2.getValue() + "\t vector key " + entry2.getKey() + "\t" + vectorBlock.size());
				rowv += entry2.getValue() * vectorBlock.get(entry2.getKey());
			}
			out += row + "," + rowv + " ";
		}
		return out;
	}
	
	//GIM-V combineAll() interface
	private static Map<Integer, Double> combineAll(Map<Integer, ArrayList<Double>> vectors){
		Map<Integer, Double> vector = new TreeMap<Integer, Double>();
		for(Map.Entry<Integer, ArrayList<Double>> entry : vectors.entrySet()){
			double sum = 0;
			for(double value : entry.getValue()){
				sum += value;
			}
			vector.put(entry.getKey(), sum);
		}
		return vector;
	}
	
	//GIM-V assign() interface
	private static String assign(Map<Integer, Double> oldV, Map<Integer, Double> newV){
		String out = "";
		for(Map.Entry<Integer, Double> entry : newV.entrySet()){
			int row = entry.getKey();
			double rowv = entry.getValue();
			out += row + "," + rowv + " ";
		}
		return out;
	}
	
	public static class GIMVMap extends MapReduceBase implements
		IterativeMapper<PairWritable, Text, PairWritable, Text, PairWritable, Text> {
		
		private int mapcount = 0;
		private int bufferedVectorINdex= -100;
		
		@Override
		public void map(PairWritable statickey, Text staticval,
				PairWritable dynamickey, Text dynamicvalue,
				OutputCollector<PairWritable, Text> output, 
				Reporter reporter) throws IOException {
			
			mapcount++;
			reporter.setStatus(String.valueOf(mapcount));
			
			Map<Integer, TreeMap<Integer, Double>> matrixBlock = new TreeMap<Integer, TreeMap<Integer, Double>>();
			Map<Integer, Double> vectorBlock = new HashMap<Integer, Double>();
			
			//read matrix to matrixBlock
			String matrixline = staticval.toString();
			System.out.println(statickey + "\t" + matrixline);
			
			StringTokenizer st = new StringTokenizer(matrixline);
			while(st.hasMoreTokens()){
				String[] field = st.nextToken().split(",");
				if(field.length == 3){
					//matrix block
					int rowIndex = Integer.parseInt(field[0]);
					int colIndex = Integer.parseInt(field[1]);
					double v = Double.parseDouble(field[2]);

					if(!matrixBlock.containsKey(rowIndex)){
						TreeMap<Integer, Double> row = new TreeMap<Integer, Double>();
						matrixBlock.put(rowIndex, row);
					}
					
					matrixBlock.get(rowIndex).put(colIndex, v);
				}else{
					throw new IOException("impossible!!");
				}
			}
			
			//read vector to vectorBlock
			String vectorline = dynamicvalue.toString();
			System.out.println(dynamickey + "\t" + vectorline);
			
			StringTokenizer st2 = new StringTokenizer(vectorline);
			while(st2.hasMoreTokens()){
				String[] field = st2.nextToken().split(",");
				if(field.length == 2){
					//vector block
					vectorBlock.put(Integer.parseInt(field[0]), Double.parseDouble(field[1]));
					//System.out.println("put " + field[0] + "\t" + field[1]);
				}else{
					throw new IOException("impossible!!");
				}
			}
			
			/**
			 * there are many repeated dynamic keys, we only output the unique key,
			 * because: each dynamic key (vector entry) corresponds to many static key (matrix block)
			 * so we have a buffer for unique key, only when the input key not equal to the buffer key
			 * we output the key value,
			 */
			if(dynamickey.getX() != bufferedVectorINdex){
				output.collect(new PairWritable(dynamickey.getX(), VECTORMARK), new Text(SELF + ":" + vectorline));
				System.out.println("output: " + new PairWritable(dynamickey.getX(), VECTORMARK) + "\t" + SELF + ":" + vectorline);
				
				bufferedVectorINdex = dynamickey.getX();
			}

			
			String out = combine2(matrixBlock, vectorBlock);
			output.collect(new PairWritable(statickey.getX(), VECTORMARK), new Text(OTHERS + ":" + out));
			System.out.println("output: " + new PairWritable(statickey.getX(), VECTORMARK) + "\t" + OTHERS + ":" + out);
		}

		@Override
		public Text removeLable() {
			return new Text("-1");
		}
	}
	
	public static class GIMVReduce extends MapReduceBase implements
		IterativeReducer<PairWritable, Text, PairWritable, Text> {
	
		private int redcount = 0;
		private long iter_start;
		private long last_iter_end;
		
		@Override
		public void configure(JobConf job){
			iter_start = job.getLong(Parameters.ITER_START, 0);
			last_iter_end = iter_start;
		}
		
		@Override
		public void reduce(PairWritable key, Iterator<Text> values,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			redcount++;
			reporter.setStatus(String.valueOf(redcount));
			
			Map<Integer, ArrayList<Double>> vectorBlock = new HashMap<Integer, ArrayList<Double>>();
			Map<Integer, Double> oldVector = new HashMap<Integer, Double>();
			
			while(values.hasNext()){
				String valueline = values.next().toString();
				
				System.out.println(key + "\t" + valueline);
				
				int index = valueline.indexOf(":");
				int mark = Integer.parseInt(valueline.substring(0, index));
				String value = valueline.substring(index+1);
				if(mark == SELF){
					StringTokenizer st = new StringTokenizer(value);
					while(st.hasMoreTokens()){
						String entry = st.nextToken();
						String[] field = entry.split(",");
						int row = Integer.parseInt(field[0]);
						double v = Double.parseDouble(field[1]);
						oldVector.put(row, v);
					}
				}else if(mark == OTHERS){
					StringTokenizer st = new StringTokenizer(value);
					while(st.hasMoreTokens()){
						String entry = st.nextToken();
						String[] field = entry.split(",");
						int row = Integer.parseInt(field[0]);
						double v = Double.parseDouble(field[1]);
						
						if(!vectorBlock.containsKey(row)){
							ArrayList<Double> vectorlist = new ArrayList<Double>();
							vectorBlock.put(row, vectorlist);
						}
						
						vectorBlock.get(row).add(v);
					}
				}
			}
	
			Map<Integer, Double> newVector = combineAll(vectorBlock);
			String out = assign(oldVector, newVector);
			
			output.collect(key, new Text(out));		
			System.out.println("output: " + key + "\t" + out);		
		}

		// use manhaten distance
		@Override
		public float distance(PairWritable key, Text prevV, Text currV)
				throws IOException {

			double change = 0;
			Map<Integer, Double> vectorBlock = new TreeMap<Integer, Double>();
			
			StringTokenizer st = new StringTokenizer(prevV.toString());
			while(st.hasMoreTokens()){
				String entry = st.nextToken();
				String[] field = entry.split(",");
				int row = Integer.parseInt(field[0]);
				double v = 0.1;
				try{
					v = Double.parseDouble(field[1]);
				}catch (Exception e){
					System.out.println("number is wrong " + prevV);
				}
				
				vectorBlock.put(row, v);
			}
			
			st = new StringTokenizer(currV.toString());
			while(st.hasMoreTokens()){
				String entry = st.nextToken();
				String[] field = entry.split(",");
				int row = Integer.parseInt(field[0]);
				double v = Double.parseDouble(field[1]);
				
				change += Math.abs(v - vectorBlock.get(row));
			}
			
			return (float)change;
		}

		@Override
		public Text removeLable() {
			// TODO Auto-generated method stub
			return null;
		}
		
		@Override
		public void iteration_complete(int iteration) {
			long curr_time = System.currentTimeMillis();
			System.out.println("iteration " + iteration + " takes " + 
					(curr_time-last_iter_end) + " total " + (curr_time-iter_start));
			last_iter_end = curr_time;
		}
	}
	
	public static class GIMVProjector implements Projector<PairWritable, PairWritable, Text> {
	
		private int rowBlockSize;
		
		@Override
		public void configure(JobConf job){
			rowBlockSize = job.getInt("matrixvector.row.blocksize", 0);
		}

		@Override
		public PairWritable project(PairWritable statickey) {
			return new PairWritable(statickey.getY(), VECTORMARK);
		}

		@Override
		public Text initDynamicV(PairWritable dynamickey) {
			int index = dynamickey.getX() * rowBlockSize;
			String out = "";
			for(int i=index; i<index+rowBlockSize; i++){
				out += i + ",0.00001 ";
			}
			return new Text(out);
		}

		/**
		 * set the dynamic key's partitioner, we use it:
		 * 1. static data distribution job: to partition the static data based on this partitioner
		 * 2. iterative processing job: to partition the dynamic data
		 */
		@Override
		public Partitioner<PairWritable, Text> getDynamicKeyPartitioner() {
			return new MatrixVectorPartitioner();
		}

		@Override
		public org.apache.hadoop.mapred.Projector.Type getProjectType() {
			return Projector.Type.ONE2MUL;
		}
	}
	
	public static class MatrixVectorPartitioner implements Partitioner<PairWritable, Text> {
		@Override
		public int getPartition(PairWritable key, Text value, int numPartitions) {
			return key.getX() % numPartitions;
		}

		@Override
		public void configure(JobConf job) {}
	}

	  
	private static void printUsage() {
		System.out.println("incrgimv <UpdateStatic> <DeltaStatic> <ConvergedValuePath> <PreservePath> <outDir>");
		System.out.println(	"\t-p # of parittions\n" +
							"\t-t filter threshold\n" +
							"\t-I # of iterations\n" +
							"\t-rb row block size\n" +
							"\t-cb column block size\n");
	}
	
	public static int main(String[] args) throws Exception {
		if (args.length < 5) {
			printUsage();
			return -1;
		}
	   
	    int partitions = 0;
		double filterthreshold = 0.1;
		int totaliter = 5;
		int rowBlockSize = 0;
		int colBlockSize = 0;

		List<String> other_args = new ArrayList<String>();
		for(int i=0; i < args.length; ++i) {
		      try {
		          if ("-I".equals(args[i])) {
		        	  totaliter = Integer.parseInt(args[++i]);
		          } else if ("-t".equals(args[i])) {
		        	  filterthreshold = Integer.parseInt(args[++i]);
		          } else if ("-p".equals(args[i])) {
		        	  partitions = Integer.parseInt(args[++i]);
		          } else if ("-rb".equals(args[i])) {
		        	  rowBlockSize = Integer.parseInt(args[++i]);
		          } else if ("-cb".equals(args[i])) {
		        	  colBlockSize = Integer.parseInt(args[++i]);
		          } else {
		    		  other_args.add(args[i]);
		    	  }
		      } catch (NumberFormatException except) {
		        System.out.println("ERROR: Integer expected instead of " + args[i]);
		        printUsage();
		        return -1;
		      } catch (ArrayIndexOutOfBoundsException except) {
		        System.out.println("ERROR: Required parameter missing from " +
		                           args[i-1]);
		        printUsage();
		        return -1;
		      }
		}
		
	    if (other_args.size() < 2) {
		      System.out.println("ERROR: Wrong number of parameters: " +
		                         other_args.size() + ".");
		      printUsage(); return -1;
		}
		
	    String updateStatic = other_args.get(0);
	    String deltaStatic = other_args.get(1);
	    String convValue = other_args.get(2);
	    String preserveState = other_args.get(3);
	    String output = other_args.get(4);
		
		String iteration_id = "incrgimv" + new Date().getTime();
 
		/**
		 * job to block the input update data
		 */
		long initstart1 = System.currentTimeMillis();
		
		JobConf job1 = new JobConf(IncrGIMV.class);
		job1.setJobName("GIM-V Blocking");
		job1.setDataDistribution(true);
		job1.setIterativeAlgorithmID(iteration_id);
		
		job1.setOutputKeyClass(PairWritable.class);
		job1.setOutputValueClass(Text.class);
		job1.setMapperClass(MatrixBlockingMapper.class);
		job1.setReducerClass(MatrixBlockingReducer.class);
		job1.setInputFormat(TextInputFormat.class);
		job1.setOutputFormat(SequenceFileOutputFormat.class);
		
		FileInputFormat.setInputPaths(job1, new Path(updateStatic));
		FileOutputFormat.setOutputPath(job1, new Path(output + "/substatic"));
		
		job1.setInt("matrixvector.row.blocksize", rowBlockSize);
		job1.setInt("matrixvector.col.blocksize", colBlockSize);
		
		job1.setProjectorClass(GIMVProjector.class);
		//job1.setPartitionerClass(MatrixVectorPartitioner.class);
		
		job1.setNumReduceTasks(partitions);
		
		JobClient.runJob(job1);

		long initend1 = System.currentTimeMillis();
		Util.writeLog("incr.gimv.log", "data distribution job use " + (initend1 - initstart1)/1000 + " s");
		
		/**
		 * job to block the input delta data
		 */
		long initstart2 = System.currentTimeMillis();
		
		JobConf job2 = new JobConf(IncrGIMV.class);
		job2.setJobName("GIM-V Blocking");
		job2.setDataDistribution(true);
		job2.setIterativeAlgorithmID(iteration_id);
		
		job2.setOutputKeyClass(PairWritable.class);
		job2.setOutputValueClass(Text.class);
		job2.setMapperClass(MatrixBlockingMapper2.class);
		job2.setReducerClass(MatrixBlockingReducer2.class);
		job2.setInputFormat(TextInputFormat.class);
		job2.setOutputFormat(SequenceFileOutputFormat.class);
		
		FileInputFormat.setInputPaths(job2, new Path(deltaStatic));
		FileOutputFormat.setOutputPath(job2, new Path(output + "/deltastatic.tmp"));	//fake output
		
		job2.setInt("matrixvector.row.blocksize", rowBlockSize);
		job2.setInt("matrixvector.col.blocksize", colBlockSize);
		job2.set("gimv.delta.update.path", output + "/deltastatic");
		
		job2.setProjectorClass(GIMVProjector.class);
		//job2.setPartitionerClass(MatrixVectorPartitioner.class);
		
		job2.setNumReduceTasks(partitions);
		
		JobClient.runJob(job2);

		long initend2 = System.currentTimeMillis();
		Util.writeLog("incr.gimv.log", "data distribution job use " + (initend2 - initstart2)/1000 + " s");
		
	    /**
	     * Incremental start job, which is the first job of the incremental jobs
	     */
    	long incrstart = System.currentTimeMillis();
    	
	    JobConf incrstartjob = new JobConf(IncrGIMV.class);
	    String jobname = "Incr GIMV Start" + new Date().getTime();
	    incrstartjob.setJobName(jobname);

	    //set for iterative process   
	    incrstartjob.setIncrementalStart(true);
	    incrstartjob.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
	    
	    incrstartjob.setDeltaUpdatePath(output + "/deltastatic"); //the delta static data
	    incrstartjob.setPreserveStatePath(preserveState);		// the preserve map/reduce output path
	    incrstartjob.setConvergeStatePath(convValue);				// the stable dynamic data path
	    //incrstartjob.setDynamicDataPath(convValue);				// the stable dynamic data path
	    incrstartjob.setIncrOutputPath(output);
	    
	    incrstartjob.setStaticInputFormat(SequenceFileInputFormat.class);
	    incrstartjob.setDynamicInputFormat(SequenceFileInputFormat.class);		//MUST have this for the following jobs, even though the first job not need it
	    incrstartjob.setResultInputFormat(SequenceFileInputFormat.class);		//if set termination check, you have to set this
	    incrstartjob.setOutputFormat(SequenceFileOutputFormat.class);
	    
	    incrstartjob.setStaticKeyClass(PairWritable.class);
	    incrstartjob.setStaticValueClass(Text.class);
	    incrstartjob.setOutputKeyClass(PairWritable.class);
	    incrstartjob.setOutputValueClass(Text.class);
	    
	    FileInputFormat.addInputPath(incrstartjob, new Path(output + "/deltastatic"));
	    FileOutputFormat.setOutputPath(incrstartjob, new Path(output + "/" + iteration_id + "/iteration-0"));	//the filtered output dynamic data

	    incrstartjob.setFilterThreshold((float)filterthreshold);

	    incrstartjob.setIterativeMapperClass(GIMVMap.class);	
	    incrstartjob.setIterativeReducerClass(GIMVReduce.class);
	    incrstartjob.setProjectorClass(GIMVProjector.class);
	    incrstartjob.setPartitionerClass(MatrixVectorPartitioner.class);
	    
	    incrstartjob.setNumReduceTasks(partitions);			

	    JobClient.runJob(incrstartjob);
	    
    	long incrend = System.currentTimeMillis();
    	long incrtime = (incrend - incrstart) / 1000;
    	Util.writeLog("incr.gimv.log", "incremental start computation takes " + incrtime + " s");
    	
    	/**
    	 * the iterative incremental jobs
    	 */
	    long itertime = 0;
	    
    	long iterstart = System.currentTimeMillis();
    	
	    JobConf incriterjob = new JobConf(IncrGIMV.class);
	    jobname = "Incr GIMV Iterative Computation " + iterstart;
	    incriterjob.setJobName(jobname);
	    incriterjob.setLong(Parameters.ITER_START, iterstart);
	    
	    //set for iterative process   
	    incriterjob.setIncrementalIterative(true);
	    incriterjob.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
	    incriterjob.setMaxIterations(totaliter);					//max number of iterations

	    incriterjob.setStaticDataPath(output + "/substatic");				//the new static data
	    incriterjob.setPreserveStatePath(preserveState);		// the preserve map/reduce output path
	    incriterjob.setDynamicDataPath(output + "/" + iteration_id);				// the dynamic data path
	    incriterjob.setIncrOutputPath(output);
	    
	    incriterjob.setStaticInputFormat(SequenceFileInputFormat.class);
	    incriterjob.setDynamicInputFormat(SequenceFileInputFormat.class);		//MUST have this for the following jobs, even though the first job not need it
	    incriterjob.setResultInputFormat(SequenceFileInputFormat.class);		//if set termination check, you have to set this
    	incriterjob.setOutputFormat(SequenceFileOutputFormat.class);
	    
    	incriterjob.setStaticKeyClass(PairWritable.class);
    	incriterjob.setStaticValueClass(Text.class);
    	incriterjob.setOutputKeyClass(PairWritable.class);
    	incriterjob.setOutputValueClass(Text.class);
	    
	    FileInputFormat.addInputPath(incriterjob, new Path(output + "/substatic"));
	    FileOutputFormat.setOutputPath(incriterjob, new Path(output + "/" + iteration_id + "/iter")); 	//the filtered output dynamic data

	    incriterjob.setFilterThreshold((float)filterthreshold);
	    incriterjob.setBufferReduceKVs(true);

	    incriterjob.setIterativeMapperClass(GIMVMap.class);	
	    incriterjob.setIterativeReducerClass(GIMVReduce.class);
	    incriterjob.setProjectorClass(GIMVProjector.class);
	    incriterjob.setPartitionerClass(MatrixVectorPartitioner.class);
	    
	    incriterjob.setNumMapTasks(partitions);
	    incriterjob.setNumReduceTasks(partitions);			

	    JobClient.runIterativeJob(incriterjob);

    	long iterend = System.currentTimeMillis();
    	itertime += (iterend - iterstart) / 1000;
    	Util.writeLog("incr.gimv.log", "iteration computation takes " + itertime + " s");
		
		return 0;
	}
}
```