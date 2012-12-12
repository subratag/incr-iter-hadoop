package org.apache.hadoop.examples.naive;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.StringTokenizer;
import java.util.TreeMap;

import org.apache.hadoop.examples.iterative.Util;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DoubleWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.Mapper;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Reducer;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.mapred.TextOutputFormat;

public class NaiveMatrixVectorMultiplication {
	
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
	
	/**
	 * initialize input data maper and reducer
	 */
	
	private static int rowBlockSize;
	private static int colBlockSize;
	private static int rowBlockNum;
	
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
			
			output.collect(new PairWritable(rowBlockIndex, colBlockIndex), new Text(row + "," + column + "," + v));
		}
	}
	
	//vector split by blocks
	public static class VectorBlockingMapper extends MapReduceBase
		implements Mapper<LongWritable, Text, PairWritable, Text> {
		
		private int rowBlockSize;
		
		@Override
		public void configure(JobConf job){
			rowBlockSize = job.getInt("matrixvector.row.blocksize", 0);
		}
		
		public void map(LongWritable key, Text value,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			String line = value.toString();

			//vector
			String[] field = line.split(" ", 2);
			if(field.length != 2) throw new IOException("input not in correct format, should be 2");
			
			int row = Integer.parseInt(field[0]);
			double v = Double.parseDouble(field[1]);
			
			int rowBlockIndex = row / rowBlockSize;
			
			output.collect(new PairWritable(rowBlockIndex,-1), new Text(row + "," + v));
		}
	}

	public static class BlockingReducer extends MapReduceBase
		implements Reducer<PairWritable, Text, PairWritable, Text> {
		@Override
		public void reduce(PairWritable key, Iterator<Text> values,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			String outputv = "";
			
			while(values.hasNext()){
				outputv += values.next().toString() + " ";
			}
			
			output.collect(key, new Text(outputv));
		}
	}
	

	
	/**
	 * matrixvector1 mapper and reducer
	 */
	public static class MatrixVectorMapper1 extends MapReduceBase
		implements Mapper<PairWritable, Text, PairWritable, Text> {

		private int mapcount = 0;
		private int rowBlockNum;
		
		@Override
		public void configure(JobConf job){
			rowBlockNum = job.getInt("matrixvector.rowblock.num", -1);
		}
		
		@Override
		public void map(PairWritable key, Text value,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			mapcount++;
			reporter.setStatus(String.valueOf(mapcount));
			
			if(key.getY() == -1){
				//vector
				for(int i=0; i<rowBlockNum; i++){
					output.collect(new PairWritable(i, key.getX()), value);
				}
				
			}else{
				//matrix
				output.collect(key, value);
			}
		}
	}
	
	public static class MatrixVectorReducer1 extends MapReduceBase
		implements Reducer<PairWritable, Text, PairWritable, Text> {

		private int redcount = 0;
		private Map<Integer, TreeMap<Integer, Double>> matrixBlock = new TreeMap<Integer, TreeMap<Integer, Double>>();
		private Map<Integer, Double> vectorBlock = new HashMap<Integer, Double>();
		
		@Override
		public void reduce(PairWritable key, Iterator<Text> values,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			redcount++;
			reporter.setStatus(String.valueOf(redcount));
			
			while(values.hasNext()){
				String line = values.next().toString();
				
				//System.out.println(key + "\t" + line);
				
				StringTokenizer st = new StringTokenizer(line);
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
					}else if(field.length == 2){
						//vector block
						vectorBlock.put(Integer.parseInt(field[0]), Double.parseDouble(field[1]));
						//System.out.println("put " + field[0] + "\t" + field[1]);
					}else{
						throw new IOException("impossible!!");
					}
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
			
			output.collect(new PairWritable(key.getX(), -1), new Text(out));		
			
		}
	}
	
	public static class MatrixVectorMapper2 extends MapReduceBase
		implements Mapper<PairWritable, Text, PairWritable, Text> {
	
		private int mapcount = 0;
		@Override
		public void map(PairWritable key, Text value,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			mapcount++;
			reporter.setStatus(String.valueOf(mapcount));
			
			if(key.getY() == -1){
				//vector
				output.collect(key, value);
			}else{
				throw new IOException("not in correct format");
			}
		}
	}
	
	public static class MatrixVectorReducer2 extends MapReduceBase
		implements Reducer<PairWritable, Text, PairWritable, Text> {
	
		private int redcount = 0;
		private Map<Integer, Double> vectorBlock = new HashMap<Integer, Double>();
		
		@Override
		public void reduce(PairWritable key, Iterator<Text> values,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			redcount++;
			reporter.setStatus(String.valueOf(redcount));
			
			while(values.hasNext()){
				String value = values.next().toString();
				
				//System.out.println(key + "\t" + value);
				
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
			
			output.collect(key, new Text(out));		
			
		}
	}

	/**
	 * termination check job
	 */
	
	public static class TermCheckMapper extends MapReduceBase
		implements Mapper<PairWritable, Text, PairWritable, Text> {

		@Override
		public void map(PairWritable key, Text value,
				OutputCollector<PairWritable, Text> output, Reporter reporter)
				throws IOException {
			output.collect(key, value);
		}
	}
	
	public static class TermCheckReducer extends MapReduceBase
		implements Reducer<PairWritable, Text, Text, DoubleWritable> {
	
		private OutputCollector<Text, DoubleWritable> collector;
		private Map<Integer, Double> vectorBlock1 = new HashMap<Integer, Double>();
		private double change = 0;

		@Override
		public void reduce(PairWritable key, Iterator<Text> values,
				OutputCollector<Text, DoubleWritable> output, Reporter reporter)
				throws IOException {
			if(collector == null) collector = output;
			int i = 0;
			while(values.hasNext()){
				i++;
				StringTokenizer st = new StringTokenizer(values.next().toString());
				while(st.hasMoreTokens()){
					String entry = st.nextToken();
					String[] field = entry.split(",");
					int row = Integer.parseInt(field[0]);
					double v = Double.parseDouble(field[1]);
					
					if(i==1){
						vectorBlock1.put(row, v);
					}
					
					if(i == 2){
						change += Math.abs(v - vectorBlock1.get(row));
					}
				}
			}
		}
		
		@Override
		public void close() throws IOException{
			collector.collect(new Text("sub change"), new DoubleWritable(change));
		}
	}
	
	
	/**
	 * @param args
	 * @throws IOException 
	 */
	public static void main(String[] args) throws IOException {
		if (args.length != 8) {
		      System.err.println("Usage: matrixvecotr <matrix> <vector> <output> <rows> <rowblocksize> <colblocksize> <partitions> <maxiteration>");
		      System.exit(2);
		}
		
		String inputmatrix = args[0];
		String inputvector = args[1];
		String output = args[2];
		int rows = Integer.parseInt(args[3]);
		rowBlockSize = Integer.parseInt(args[4]);
		colBlockSize = Integer.parseInt(args[5]);
		int partitions = Integer.parseInt(args[6]);
		int maxiteration = Integer.parseInt(args[7]);
		
		long initstart = System.currentTimeMillis();

		/**
		 * job to block the input data
		 */
		JobConf conf1 = new JobConf(NaiveMatrixVectorMultiplication.class);
		conf1.setJobName("Matrix Blocking");
		conf1.setOutputKeyClass(PairWritable.class);
		conf1.setOutputValueClass(Text.class);
		conf1.setMapperClass(MatrixBlockingMapper.class);
		conf1.setReducerClass(BlockingReducer.class);
		conf1.setInputFormat(TextInputFormat.class);
		conf1.setOutputFormat(SequenceFileOutputFormat.class);
		FileInputFormat.setInputPaths(conf1, new Path(inputmatrix));
		FileOutputFormat.setOutputPath(conf1, new Path(output + "/matrix"));
		conf1.setNumReduceTasks(partitions);
		conf1.setInt("matrixvector.row.blocksize", rowBlockSize);
		conf1.setInt("matrixvector.col.blocksize", colBlockSize);
		JobClient.runJob(conf1);
		
		JobConf conf2 = new JobConf(NaiveMatrixVectorMultiplication.class);
		conf2.setJobName("Vector Blocking");
		conf2.setOutputKeyClass(PairWritable.class);
		conf2.setOutputValueClass(Text.class);
		conf2.setMapperClass(VectorBlockingMapper.class);
		conf2.setReducerClass(BlockingReducer.class);
		conf2.setInputFormat(TextInputFormat.class);
		conf2.setOutputFormat(SequenceFileOutputFormat.class);
		FileInputFormat.setInputPaths(conf2, new Path(inputvector));
		FileOutputFormat.setOutputPath(conf2, new Path(output + "/vector/iteration-0"));
		conf2.setNumReduceTasks(partitions);
		conf2.setInt("matrixvector.row.blocksize", rowBlockSize);
		JobClient.runJob(conf2);
		
		long initend = System.currentTimeMillis();
		Util.writeLog("naive.matrixvector.log", "init job use " + (initend - initstart)/1000 + " s");
		
		long itertime = 0;
		long totaltime = 0;
		int iteration = 0;
		
		rowBlockNum = (int) Math.ceil((double)rows/rowBlockSize);
		
		long start = System.currentTimeMillis();
		do {
			iteration++;
			/****************** Main Job1 ********************************/
			long iterstart = System.currentTimeMillis();;
			JobConf conf3 = new JobConf(NaiveMatrixVectorMultiplication.class);
			conf3.setJobName("MatrixVector-Main1");

			conf3.setOutputKeyClass(PairWritable.class);
			conf3.setOutputValueClass(Text.class);

			conf3.setMapperClass(MatrixVectorMapper1.class);
			conf3.setReducerClass(MatrixVectorReducer1.class);
			conf3.setInputFormat(SequenceFileInputFormat.class);
			conf3.setOutputFormat(SequenceFileOutputFormat.class);

			FileInputFormat.addInputPaths(conf3, output + "/matrix");
			FileInputFormat.addInputPaths(conf3, output + "/vector/iteration-" + (iteration-1));
			FileOutputFormat.setOutputPath(conf3, new Path(output + "/vector/iteration-" + iteration + "-intermediate"));
			conf3.setNumReduceTasks(partitions);
			conf3.setInt("matrixvector.rowblock.num", rowBlockNum);

			JobClient.runJob(conf3);
			
			/****************** Main Job2 ********************************/
			JobConf conf4 = new JobConf(NaiveMatrixVectorMultiplication.class);
			conf4.setJobName("MatrixVector-Main2");

			conf4.setOutputKeyClass(PairWritable.class);
			conf4.setOutputValueClass(Text.class);

			conf4.setMapperClass(MatrixVectorMapper2.class);
			conf4.setReducerClass(MatrixVectorReducer2.class);
			conf4.setInputFormat(SequenceFileInputFormat.class);
			conf4.setOutputFormat(SequenceFileOutputFormat.class);

			FileInputFormat.addInputPaths(conf4, output + "/vector/iteration-" + iteration + "-intermediate");
			FileOutputFormat.setOutputPath(conf4, new Path(output + "/vector/iteration-" + (iteration)));
			conf4.setNumReduceTasks(partitions);

			JobClient.runJob(conf4);
			
			long iterend = System.currentTimeMillis();
			itertime += (iterend - iterstart) / 1000;
			
			/******************** Termination Check Job ***********************/
			JobConf conf5 = new JobConf(NaiveMatrixVectorMultiplication.class);
			conf5.setJobName("MatrixVector-TermCheck");

			conf5.setMapOutputKeyClass(PairWritable.class);
			conf5.setMapOutputValueClass(Text.class);
			conf5.setOutputKeyClass(Text.class);
			conf5.setOutputValueClass(DoubleWritable.class);

			conf5.setMapperClass(TermCheckMapper.class);
			conf5.setReducerClass(TermCheckReducer.class);

			conf5.setInputFormat(SequenceFileInputFormat.class);
			conf5.setOutputFormat(TextOutputFormat.class);

			FileInputFormat.setInputPaths(conf5, new Path(output + "/vector/iteration-" + (iteration-1)), new Path(output + "/vector/iteration-" + (iteration)));
			FileOutputFormat.setOutputPath(conf5, new Path(output + "/vector/termcheck-" + iteration));
			conf5.setNumReduceTasks(partitions);

			JobClient.runJob(conf5);
			
			long termend = System.currentTimeMillis();
			totaltime += (termend - iterstart) / 1000;
			
			Util.writeLog("naive.matrixvector.log", "iteration computation " + iteration + " takes " + itertime + " s, include termination check takes " + totaltime);
			
		} while (iteration < maxiteration);
    }
}
