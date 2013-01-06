package org.apache.hadoop.examples.iterative;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;


import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IntWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
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
import org.apache.hadoop.mapred.lib.HashPartitioner;

public class MatrixVector {
	
	/**
	 * initialize input data maper and reducer
	 */
	
	public static class PairWritable implements WritableComparable {
		private int x;
		private int y;
		
		public PairWritable(){}
		
		public PairWritable(int a, int b){
			x = a;
			y = b;
		}
		
		public int getX() {return x;}
		public int getY() {return y;}
		
		@Override
		public void write(DataOutput out) throws IOException {
			out.writeInt(x);
			out.writeInt(y);
		}

		@Override
		public void readFields(DataInput in) throws IOException {
			x = in.readInt();
			y = in.readInt();
		}

		@Override
		public int compareTo(Object o) {
			PairWritable obj = (PairWritable)o;
			if(x == obj.x){
				return (y > obj.y) ? 1 : (y < obj.y) ? -1 : 0;
			}else{
				return (x > obj.x) ? 1 : -1;
			}
		}
		
		@Override
		public int hashCode(){
			return x*37+y;
		}
		
		@Override
		public String toString(){
			return x + ":" + y;
		}
	}
	
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
	 * matrixvector1 mapper and reducer
	 */
	public static class MatrixVectorMapper extends MapReduceBase
		implements IterativeMapper<PairWritable, Text, IntWritable, Text, IntWritable, Text> {

		private int mapcount = 0;
		private Map<Integer, TreeMap<Integer, Double>> matrixBlock = new TreeMap<Integer, TreeMap<Integer, Double>>();
		private Map<Integer, Double> vectorBlock = new TreeMap<Integer, Double>();
		
		@Override
		public void map(PairWritable key, Text staticvalue, Text dyanmicvalue,
				OutputCollector<IntWritable, Text> output, Reporter reporter)
				throws IOException {
			mapcount++;
			reporter.setStatus(String.valueOf(mapcount));
			
			String matrixline = staticvalue.toString();
			
			System.out.println(key + "\t" + matrixline);
			
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
			
			String vectorline = dyanmicvalue.toString();
			
			System.out.println(key + "\t" + vectorline);
			
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
			matrixBlock.clear();
			vectorBlock.clear();
			
			System.out.println("output: " + key.getX() + "\t" + out);
			
			output.collect(new IntWritable(key.getX()), new Text(out));	
		}

		@Override
		public Text removeLable() {
			// TODO Auto-generated method stub
			return null;
		}
	}
	
	public static class MatrixVectorReducer extends MapReduceBase
		implements IterativeReducer<IntWritable, Text, IntWritable, Text> {

		private int redcount = 0;
		private Map<Integer, Double> vectorBlock = new TreeMap<Integer, Double>();
		
		@Override
		public void reduce(IntWritable key, Iterator<Text> values,
				OutputCollector<IntWritable, Text> output, Reporter reporter)
				throws IOException {
			redcount++;
			reporter.setStatus(String.valueOf(redcount));
			
			while(values.hasNext()){
				String value = values.next().toString();
				
				System.out.println("input: " + key + "\t" + value);
				
				StringTokenizer st = new StringTokenizer(value);
				while(st.hasMoreTokens()){
					String entry = st.nextToken();
					String[] field = entry.split(",");
					int row = Integer.parseInt(field[0]);
					double v = Double.parseDouble(field[1]);
					
					if(vectorBlock.containsKey(row)){
						vectorBlock.put(row, vectorBlock.get(row) + v);
					}else{
						vectorBlock.put(row, v);
					}
				}
			}
	
			String out = "";
			for(Map.Entry<Integer, Double> entry : vectorBlock.entrySet()){
				int row = entry.getKey();
				double rowv = entry.getValue();
				out += row + "," + rowv + " ";
			}
			vectorBlock.clear();
			
			System.out.println("output: " + key + "\t" + out);
			
			output.collect(key, new Text(out));			
		}

		@Override
		public float distance(IntWritable key, Text prevV, Text currV)
				throws IOException {

			double change = 0;
			
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
			
			vectorBlock.clear();
			
			return (float)change;
		}

		@Override
		public Text removeLable() {
			// TODO Auto-generated method stub
			return null;
		}
	}

	public static class MatrixVectorProjector implements Projector<PairWritable, IntWritable, Text> {

		private int rowBlockSize;
		
		@Override
		public void configure(JobConf job){
			rowBlockSize = job.getInt("matrixvector.row.blocksize", 0);
		}

		@Override
		public IntWritable project(PairWritable statickey) {
			return new IntWritable(statickey.getY());
		}

		@Override
		public Text initDynamicV(IntWritable dynamickey) {
			int index = dynamickey.get()*rowBlockSize;
			String out = "";
			for(int i=index; i<index+rowBlockSize; i++){
				out += i + ",1 ";
			}
			return new Text(out);
		}

		@Override
		public Partitioner<IntWritable, Text> getDynamicKeyPartitioner() {
			// TODO Auto-generated method stub
			return new HashPartitioner<IntWritable, Text>();
		}

		@Override
		public org.apache.hadoop.mapred.Projector.Type getProjectType() {
			return Projector.Type.ONE2MUL;
		}
	}
	
	private static void printUsage() {
		System.out.println("matrixvector <inStaticDir> <outDir>");
		System.out.println(	"\t-p # of parittions\n" +
							"\t-i snapshot interval\n" +
							"\t-I # of iterations\n" +
							"\t-n # of nodes");
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
		int max_iterations = Integer.MAX_VALUE;
		int rowBlockSize = 0;
		int colBlockSize = 0;
		
		List<String> other_args = new ArrayList<String>();
		for(int i=0; i < args.length; ++i) {
		      try {
		          if ("-p".equals(args[i])) {
		        	partitions = Integer.parseInt(args[++i]);
		          } else if ("-i".equals(args[i])) {
		        	interval = Integer.parseInt(args[++i]);
		          } else if ("-I".equals(args[i])) {
		        	  max_iterations = Integer.parseInt(args[++i]);
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
		
	    String inStatic = other_args.get(0);
	    String output = other_args.get(1);
		
		long initstart = System.currentTimeMillis();

		String iteration_id = "matrixvector" + new Date().getTime();
		
		/**
		 * job to block the input data
		 */
		JobConf job1 = new JobConf(MatrixVector.class);
		job1.setJobName("Matrix Blocking");
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
		
		job1.setProjectorClass(MatrixVectorProjector.class);
		
		job1.setNumReduceTasks(partitions);
		
		JobClient.runJob(job1);

		long initend = System.currentTimeMillis();
		Util.writeLog("iter.matrixvector.log", "init job use " + (initend - initstart)/1000 + " s");
		
	    /**
	     * start iterative application jobs
	     */
	    
	    int iteration = 1;
	    boolean cont = true;
	    
	    long itertime = 0;
		while(cont && iteration < max_iterations) {
			/****************** Main Job1 ********************************/
			long iterstart = System.currentTimeMillis();;
			JobConf job2 = new JobConf(MatrixVector.class);
			job2.setJobName("MatrixVector-Main " + iteration);

			job2.setIterative(true);
			job2.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
			job2.setIterationNum(iteration);					//iteration number
			job2.setCheckPointInterval(interval);					//checkpoint interval
			job2.setStaticDataPath(output + "/substatic");
			job2.setStaticInputFormat(SequenceFileInputFormat.class);
			job2.setDynamicInputFormat(SequenceFileInputFormat.class);		//MUST have this for the following jobs, even though the first job not need it
			job2.setResultInputFormat(SequenceFileInputFormat.class);		//if set termination check, you have to set this
			job2.setOutputFormat(SequenceFileOutputFormat.class);

			FileInputFormat.addInputPaths(job2, output + "/substatic");
			FileOutputFormat.setOutputPath(job2, new Path(output + "/iteration-" + iteration));
			
		    if(max_iterations == Integer.MAX_VALUE){
		    	job2.setDistanceThreshold(1);
		    }
    
		    job2.setOutputKeyClass(IntWritable.class);
		    job2.setOutputValueClass(Text.class);
		    
		    job2.setInt("matrixvector.row.blocksize", rowBlockSize);
		    
		    job2.setIterativeMapperClass(MatrixVectorMapper.class);	
		    job2.setIterativeReducerClass(MatrixVectorReducer.class);
		    job2.setProjectorClass(MatrixVectorProjector.class);
		    
		    job2.setNumReduceTasks(partitions);

			cont = JobClient.runIterativeJob(job2);
			
	    	long iterend = System.currentTimeMillis();
	    	itertime += (iterend - iterstart) / 1000;
	    	Util.writeLog("iter.matrixvector.log", "iteration computation " + iteration + " takes " + itertime + " s");
	    	
	    	iteration++;
		};
		
		return 0;
    }
}
