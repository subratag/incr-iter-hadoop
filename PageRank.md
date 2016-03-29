# Introduction #

PageRank is a well-know iterative graph algorithm for ranking webpages. In the webgraph, each node represents a webpage, and each edge represents a hyperlink between webpages. The iterative algorithm starts by initializing each node $i$ with value $R(i)$, which is the ranking score to be updated iteratively. In each iteration, each node sends value $R\_i(j)=R(i)/|L(i)|$ to all its neighbors $j$, where $L(i)$ is node $i$'s neighbor set. After a node $j$ receives all the values from various $i$, it updates its value by $R(j)=d\sum\_i(R\_i(j))+(1-d)$, where $d$ is a damping factor. In PageRank, ranking score $R(i)$ is updated iteratively, which is the state data. Each node's neighbor set $L(i)$ is invariant during iterative computation, which is the structure data. The correlation between structure kv and state kv is \textbf{one-to-one}, since the computation on node $i$ needs $R(i)$ and $L(i)$. PageRank is implemented in i$^2$MapReduce as follows.

# Details #


### Iterative PageRank ###

```
package org.apache.hadoop.examples.iterative;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.StringTokenizer;

import org.apache.hadoop.examples.utils.Parameters;
import org.apache.hadoop.examples.utils.Util;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.IterativeMapper;
import org.apache.hadoop.mapred.IterativeReducer;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.KeyValueTextInputFormat;
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


public class IterPageRank {
	
	//damping factor
	public static final float DAMPINGFAC = (float)0.8;
	public static final float RETAINFAC = (float)0.2;
	
	public static class DistributeDataMap extends MapReduceBase implements
		Mapper<Text, Text, LongWritable, Text> {
		
		@Override
		public void map(Text arg0, Text value,
				OutputCollector<LongWritable, Text> arg2, Reporter arg3)
				throws IOException {
			int page = Integer.parseInt(arg0.toString());
		
			arg2.collect(new LongWritable(page), value);
		}
	}
	
	public static class DistributeDataReduce extends MapReduceBase implements
		Reducer<LongWritable, Text, LongWritable, Text> {
		Random rand = new Random();
		@Override
		public void reduce(LongWritable key, Iterator<Text> values,
				OutputCollector<LongWritable, Text> output, Reporter reporter)
				throws IOException {
			String outputv = "";

			while(values.hasNext()){
				String end = values.next().toString();
				
				outputv += end + " ";
			}
			
			output.collect(key, new Text(outputv));
		}
	}
	
	public static class DistributeDataMap2 extends MapReduceBase implements
		Mapper<LongWritable, Text, LongWritable, Text> {
		
		@Override
		public void map(LongWritable arg0, Text value,
				OutputCollector<LongWritable, Text> arg2, Reporter arg3)
				throws IOException {
			arg2.collect(arg0, value);
		}
	}

	public static class DistributeDataReduce2 extends MapReduceBase implements
		Reducer<LongWritable, Text, LongWritable, Text> {
		Random rand = new Random();
		@Override
		public void reduce(LongWritable key, Iterator<Text> values,
				OutputCollector<LongWritable, Text> output, Reporter reporter)
				throws IOException {
			while(values.hasNext()){
				output.collect(key, values.next());
			}
		}
	}
	
	public static class DistributeDataMap3 extends MapReduceBase implements
		Mapper<LongWritable, Text, LongWritable, Text> {
		
		private LongWritable outputKey = new LongWritable();
		private Text outputVal = new Text();
		private List<String> tokenList = new ArrayList<String>();

		public void map(LongWritable key, Text value,
				OutputCollector<LongWritable, Text> output, Reporter reporter)
				throws IOException {
			tokenList.clear();

			String line = value.toString();
			StringTokenizer tokenizer = new StringTokenizer(line);

			while (tokenizer.hasMoreTokens()) {
				tokenList.add(tokenizer.nextToken());
			}

			if (tokenList.size() >= 2) {
				outputKey.set(Integer.parseInt(tokenList.get(0)));
				outputVal.set(tokenList.get(1).getBytes());
				output.collect(outputKey, outputVal);
			} else if(tokenList.size() == 1){
				//no out link
				System.out.println("node " + key + " has no out links");
			}
		}
	}
	
	public static class DistributeDataReduce3 extends MapReduceBase implements
		Reducer<LongWritable, Text, LongWritable, Text> {
		Random rand = new Random();
		@Override
		public void reduce(LongWritable key, Iterator<Text> values,
				OutputCollector<LongWritable, Text> output, Reporter reporter)
				throws IOException {
			String outputv = "";
			String nodestr = String.valueOf(key.get());
			
			while(values.hasNext()){
				String end = values.next().toString();
				if(end.equals(nodestr)){
					outputv += "0 ";
				}else{
					outputv += end + " ";
				}
			}
			
			output.collect(key, new Text(outputv));
		}
	}
	
	public static class PageRankMap extends MapReduceBase implements
		IterativeMapper<LongWritable, Text, LongWritable, FloatWritable, LongWritable, FloatWritable> {
	
		@Override
		public void map(LongWritable statickey, Text staticval,
				LongWritable dynamickey, FloatWritable dynamicvalue,
				OutputCollector<LongWritable, FloatWritable> output,
				Reporter reporter) throws IOException {
			
			float rank = dynamicvalue.get();
			String linkstring = staticval.toString();
			
			//in order to avoid non-inlink node, which will mismatch the static file
			output.collect(statickey, new FloatWritable(IterPageRank.RETAINFAC));
			
			String[] links = linkstring.split(" ");	
			float delta = rank * IterPageRank.DAMPINGFAC / links.length;
			
			for(String link : links){
				if(link.equals("")) continue;
				output.collect(new LongWritable(Long.parseLong(link)), new FloatWritable(delta));
			}
		}

		@Override
		public FloatWritable removeLable() {
			return null;
		}
	}
	
	public static class PageRankReduce extends MapReduceBase implements
		IterativeReducer<LongWritable, FloatWritable, LongWritable, FloatWritable> {
	
		private long iter_start;
		private long last_iter_end;
		
		@Override
		public void configure(JobConf job){
			iter_start = job.getLong(Parameters.ITER_START, 0);
			last_iter_end = iter_start;
		}
		
		@Override
		public void reduce(LongWritable key, Iterator<FloatWritable> values,
				OutputCollector<LongWritable, FloatWritable> output, Reporter report)
				throws IOException {
			float rank = 0;
			while(values.hasNext()){
				float v = values.next().get();
				if(v == -1) continue;
				rank += v;
			}
			
			output.collect(key, new FloatWritable(rank));
		}
		
		@Override
		public float distance(LongWritable key, FloatWritable prevV,
				FloatWritable currV) throws IOException {
			return Math.abs(prevV.get() - currV.get());
		}

		@Override
		public FloatWritable removeLable() {
			return null;
		}

		@Override
		public void iteration_complete(int iteration) {
			long curr_time = System.currentTimeMillis();
			System.out.println("iteration " + iteration + " takes " + 
					(curr_time-last_iter_end) + " total " + (curr_time-iter_start));
		}
	}
	
	public static class PageRankProjector implements Projector<LongWritable, LongWritable, FloatWritable> {
		@Override
		public LongWritable project(LongWritable statickey) {
			return statickey;
		}

		@Override
		public FloatWritable initDynamicV(LongWritable dynamickey) {
			return new FloatWritable(1);
		}

		@Override
		public Partitioner<LongWritable, FloatWritable> getDynamicKeyPartitioner() {
			return new HashPartitioner<LongWritable, FloatWritable>();
		}

		@Override
		public org.apache.hadoop.mapred.Projector.Type getProjectType() {
			return Projector.Type.ONE2ONE;
		}
	}

	private static void printUsage() {
		System.out.println("pagerank [-p partitions] <inStaticDir> <outDir>");
		System.out.println(	"\t-p # of parittions\n" +
							"\t-i snapshot interval\n" +
							"\t-I # of iterations\n" +
							"\t-D initial dynamic path\n" +
							"\t-f input format\n" + 
							"\t-s run preserve job");
	}

	public static int main(String[] args) throws Exception {
		if (args.length < 2) {
			System.out.println("ERROR: Wrong Input Parameters!");
	        printUsage();
	        return -1;
		}
		
		int partitions = 0;
		int interval = 1;
		int max_iterations = Integer.MAX_VALUE;
		String init_dynamic = "";
		String data_format = "";
		boolean preserve = true;
		
		List<String> other_args = new ArrayList<String>();
		for(int i=0; i < args.length; ++i) {
		      try {
		          if ("-p".equals(args[i])) {
		        	partitions = Integer.parseInt(args[++i]);
		          } else if ("-i".equals(args[i])) {
		        	interval = Integer.parseInt(args[++i]);
		          } else if ("-I".equals(args[i])) {
		        	  max_iterations = Integer.parseInt(args[++i]);
		          } else if ("-D".equals(args[i])) {
		        	  init_dynamic = args[++i];
		          } else if ("-f".equals(args[i])) {
		        	  data_format = args[++i];
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
		
	    String iteration_id = "pagerank" + new Date().getTime();
	    
		/**
		 * the initialization job, for partition the data and workload
		 */
	    long initstart = System.currentTimeMillis();
	    
	    JobConf job1 = new JobConf(IterPageRank.class);
	    String jobname1 = "PageRank Init";
	    job1.setJobName(jobname1);
	    
	    job1.setDataDistribution(true);
	    job1.setIterativeAlgorithmID(iteration_id);
	    
	    if(data_format.equals("KeyValueTextInputFormat")){
	    	job1.setInputFormat(KeyValueTextInputFormat.class);
	    	job1.setMapperClass(DistributeDataMap.class);
	    	job1.setReducerClass(DistributeDataReduce.class);
	    }else if(data_format.equals("SequenceFileInputFormat")){
	    	job1.setInputFormat(SequenceFileInputFormat.class);
	    	job1.setMapperClass(DistributeDataMap2.class);
	    	job1.setReducerClass(DistributeDataReduce2.class);
	    }else if(data_format.equals("TextInputFormat")){
	    	job1.setInputFormat(TextInputFormat.class);
	    	job1.setMapperClass(DistributeDataMap3.class);
	    	job1.setReducerClass(DistributeDataReduce3.class);
	    }
	    
	    job1.setOutputFormat(SequenceFileOutputFormat.class);
	    FileInputFormat.addInputPath(job1, new Path(inStatic));
	    FileOutputFormat.setOutputPath(job1, new Path(output + "/substatic"));

	    job1.setOutputKeyClass(LongWritable.class);
	    job1.setOutputValueClass(Text.class);
	    
	    job1.setProjectorClass(PageRankProjector.class);
	    
	    /**
	     * if partitions to0 small, which limit the map performance (since map is usually more haveyly loaded),
	     * we should partition the static data into 2*partitions, and copy reduce results to the other mappers in the same scale,
	     * buf first, we just consider the simple case, static data partitions == dynamic data partitions
	     */
	    job1.setNumMapTasks(partitions);
	    job1.setNumReduceTasks(partitions);
	    
	    JobClient.runJob(job1);
	    
	    long initend = System.currentTimeMillis();
		Util.writeLog("iter.pagerank.log", "init job use " + (initend - initstart)/1000 + " s");
		
	    /**
	     * start iterative application jobs
	     */
	    long itertime = 0;
	    
	    //while(cont && iteration < max_iterations){
    	long iterstart = System.currentTimeMillis();
    	
	    JobConf job = new JobConf(IterPageRank.class);
	    String jobname = "Iter PageRank ";
	    job.setJobName(jobname);

	    //set for iterative process   
	    job.setIterative(true);
	    job.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
	    job.setLong(Parameters.ITER_START, iterstart);

	    if(max_iterations == Integer.MAX_VALUE){
	    	job.setDistanceThreshold(1);
	    }else{
	    	job.setMaxIterations(max_iterations);
	    }
	    
	    if(init_dynamic == ""){
	    	job.setInitWithFileOrApp(false);
	    }else{
	    	job.setInitWithFileOrApp(true);
	    	job.setInitStatePath(init_dynamic);
	    }
	    job.setStaticDataPath(output + "/substatic");
	    job.setDynamicDataPath(output + "/result");	
	    
	    job.setStaticInputFormat(SequenceFileInputFormat.class);
	    job.setDynamicInputFormat(SequenceFileInputFormat.class);		//MUST have this for the following jobs, even though the first job not need it
    	job.setResultInputFormat(SequenceFileInputFormat.class);		//if set termination check, you have to set this
	    job.setOutputFormat(SequenceFileOutputFormat.class);
	    
	    FileInputFormat.addInputPath(job, new Path(output + "/substatic"));
	    FileOutputFormat.setOutputPath(job, new Path(output + "/result"));
	    
	    job.setOutputKeyClass(LongWritable.class);
	    job.setOutputValueClass(FloatWritable.class);
	    
	    job.setIterativeMapperClass(PageRankMap.class);	
	    job.setIterativeReducerClass(PageRankReduce.class);
	    job.setProjectorClass(PageRankProjector.class);
	    
	    job.setNumReduceTasks(partitions);			
	    JobClient.runIterativeJob(job);

    	long iterend = System.currentTimeMillis();
    	itertime += (iterend - iterstart) / 1000;
    	Util.writeLog("iter.pagerank.log", "iteration computation takes " + itertime + " s");
	    	
    	
	    if(preserve){
		    //preserving job
	    	long preservestart = System.currentTimeMillis();
	    	
		    JobConf job2 = new JobConf(IterPageRank.class);
		    jobname = "PageRank Preserve ";
		    job2.setJobName(jobname);
	    
		    if(partitions == 0) partitions = Util.getTTNum(job2);
		    
		    //set for iterative process   
		    job2.setPreserve(true);
		    job2.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
		    //job2.setIterationNum(iteration);					//iteration numbe
		    job2.setCheckPointInterval(interval);					//checkpoint interval
		    job2.setStaticDataPath(output + "/substatic");
		    job2.setDynamicDataPath(output + "/result/iteration-" + max_iterations);	
		    job2.setStaticInputFormat(SequenceFileInputFormat.class);
		    job2.setDynamicInputFormat(SequenceFileInputFormat.class);		//MUST have this for the following jobs, even though the first job not need it
	    	job2.setResultInputFormat(SequenceFileInputFormat.class);		//if set termination check, you have to set this
		    job2.setOutputFormat(SequenceFileOutputFormat.class);
		    job2.setPreserveStatePath(output + "/preserve");
		    
		    FileInputFormat.addInputPath(job2, new Path(output + "/substatic"));
		    FileOutputFormat.setOutputPath(job2, new Path(output + "/preserve/convergeState"));
		    
		    if(max_iterations == Integer.MAX_VALUE){
		    	job2.setDistanceThreshold(1);
		    }

		    job2.setStaticKeyClass(LongWritable.class);
		    job2.setOutputKeyClass(LongWritable.class);
		    job2.setOutputValueClass(FloatWritable.class);
		    
		    job2.setIterativeMapperClass(PageRankMap.class);	
		    job2.setIterativeReducerClass(PageRankReduce.class);
		    job2.setProjectorClass(PageRankProjector.class);
		    
		    job2.setNumReduceTasks(partitions);			

		    JobClient.runIterativeJob(job2);

	    	long preserveend = System.currentTimeMillis();
	    	long preservationtime = (preserveend - preservestart) / 1000;
	    	Util.writeLog("iter.pagerank.log", "iteration preservation takes " + preservationtime + " s");
	    }
		return 0;
	}

}
```

### Incremental PageRank ###

```
package org.apache.hadoop.examples.incremental;

import java.io.IOException;
import java.util.Date;
import java.util.Iterator;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.FloatWritable;
import org.apache.hadoop.io.LongWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapred.FileInputFormat;
import org.apache.hadoop.mapred.FileOutputFormat;
import org.apache.hadoop.mapred.IterativeMapper;
import org.apache.hadoop.mapred.IterativeReducer;
import org.apache.hadoop.mapred.JobClient;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.MapReduceBase;
import org.apache.hadoop.mapred.OutputCollector;
import org.apache.hadoop.mapred.Partitioner;
import org.apache.hadoop.mapred.Projector;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.SequenceFileInputFormat;
import org.apache.hadoop.mapred.SequenceFileOutputFormat;
import org.apache.hadoop.mapred.lib.HashPartitioner;
import org.apache.hadoop.examples.utils.Parameters;
import org.apache.hadoop.examples.utils.Util;

public class IncrPageRank {

	//damping factor
	public static final float DAMPINGFAC = (float)0.8;
	public static final float RETAINFAC = (float)0.2;
	

	public static class PageRankMap extends MapReduceBase implements
		IterativeMapper<LongWritable, Text, LongWritable, FloatWritable, LongWritable, FloatWritable> {
		
		@Override
		public void map(LongWritable statickey, Text staticval,
				LongWritable dynamickey, FloatWritable dynamicvalue,
				OutputCollector<LongWritable, FloatWritable> output,
				Reporter reporter) throws IOException {
			
			float rank = dynamicvalue.get();
			//System.out.println("input : " + statickey + " : " + rank);
			String linkstring = staticval.toString();
			
			//in order to avoid non-inlink node, which will mismatch the static file
			output.collect(statickey, new FloatWritable(RETAINFAC));
			
			String[] links = linkstring.split(" ");	
			float delta = rank * DAMPINGFAC / links.length;
			
			for(String link : links){
				if(link.equals("")) continue;
				output.collect(new LongWritable(Long.parseLong(link)), new FloatWritable(delta));
				//System.out.println("output: " + link + "\t" + delta);
			}
		}

		@Override
		public FloatWritable removeLable() {
			return new FloatWritable(-1);
		}
	}
	
	public static class PageRankReduce extends MapReduceBase implements
		IterativeReducer<LongWritable, FloatWritable, LongWritable, FloatWritable> {
	
		private long iter_start;
		private long last_iter_end;
		
		@Override
		public void configure(JobConf job){
			iter_start = job.getLong(Parameters.ITER_START, 0);
			last_iter_end = iter_start;
		}
		
		@Override
		public void reduce(LongWritable key, Iterator<FloatWritable> values,
				OutputCollector<LongWritable, FloatWritable> output, Reporter report)
				throws IOException {
			float rank = 0;
			
			int i = 0;
			while(values.hasNext()){
				float v = values.next().get();
				if(v == -1) continue;	//if the value is equal to the one set by removeLable(), we skip it
				
				i++;
				rank += v;
				
				//System.out.println("reduce on " + key + " with " + v);
			}
			
			//System.out.println(" key " + key + " with " + i);
			
			output.collect(key, new FloatWritable(rank));
			//System.out.println("output\t" + key + "\t" + rank);
		}
		
		@Override
		public float distance(LongWritable key, FloatWritable prevV,
				FloatWritable currV) throws IOException {
			// TODO Auto-generated method stub
			return Math.abs(prevV.get() - currV.get());
		}

		@Override
		public FloatWritable removeLable() {
			// TODO Auto-generated method stub
			return new FloatWritable(-1);
		}

		@Override
		public void iteration_complete(int iteration) {
			long curr_time = System.currentTimeMillis();
			System.out.println("iteration " + iteration + " takes " + 
					(curr_time-last_iter_end) + " total " + (curr_time-iter_start));
			last_iter_end = curr_time;
		}
	}

	public static class PageRankProjector implements Projector<LongWritable, LongWritable, FloatWritable> {

		@Override
		public void configure(JobConf job) {
			// TODO Auto-generated method stub
			
		}

		@Override
		public LongWritable project(LongWritable statickey) {
			return statickey;
		}

		@Override
		public FloatWritable initDynamicV(LongWritable dynamickey) {
			return new FloatWritable(1);
		}

		@Override
		public Partitioner<LongWritable, FloatWritable> getDynamicKeyPartitioner() {
			// TODO Auto-generated method stub
			return new HashPartitioner<LongWritable, FloatWritable>();
		}

		@Override
		public org.apache.hadoop.mapred.Projector.Type getProjectType() {
			return Projector.Type.ONE2ONE;
		}
	}
	
	private static void printUsage() {
		System.out.println("incrpagerank <UpdateStatic> <DeltaStatic> <ConvergedValuePath> <PreservePath> <outDir> " +
				"<partitions> <filterthreshold> <totaliter> <inmemreduce>");
	}

	public static int main(String[] args) throws Exception {
		if (args.length < 9) {
			printUsage();
			return -1;
		}
	    
	    String updateStatic = args[0];
	    String deltaStatic = args[1];
	    String convValue = args[2];
	    String preserveState = args[3];
	    String output = args[4];
	    int partitions = Integer.parseInt(args[5]);
		double filterthreshold = Double.parseDouble(args[6]);
		int totaliter = Integer.parseInt(args[7]);
		boolean inmemreduce = Boolean.parseBoolean(args[8]);

		String iteration_id = "incrpagerank" + new Date().getTime();
 
	    /**
	     * Incremental start job, which is the first job of the incremental jobs
	     */
    	long incrstart = System.currentTimeMillis();
    	
	    JobConf incrstartjob = new JobConf(IncrPageRank.class);
	    String jobname = "Incr PageRank Start" + new Date().getTime();
	    incrstartjob.setJobName(jobname);

	    //set for iterative process   
	    incrstartjob.setIncrementalStart(true);
	    incrstartjob.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
	    
	    incrstartjob.setDeltaUpdatePath(deltaStatic);				//the out dated static data
	    incrstartjob.setPreserveStatePath(preserveState);		// the preserve map/reduce output path
	    incrstartjob.setConvergeStatePath(convValue);				// the stable dynamic data path
	    //incrstartjob.setDynamicDataPath(convValue);				// the stable dynamic data path
	    incrstartjob.setIncrOutputPath(output);
	    
	    incrstartjob.setStaticInputFormat(SequenceFileInputFormat.class);
	    incrstartjob.setDynamicInputFormat(SequenceFileInputFormat.class);		//MUST have this for the following jobs, even though the first job not need it
	    incrstartjob.setResultInputFormat(SequenceFileInputFormat.class);		//if set termination check, you have to set this
	    incrstartjob.setOutputFormat(SequenceFileOutputFormat.class);
	    
	    incrstartjob.setStaticKeyClass(LongWritable.class);
	    incrstartjob.setStaticValueClass(Text.class);
	    incrstartjob.setOutputKeyClass(LongWritable.class);
	    incrstartjob.setOutputValueClass(FloatWritable.class);
	    
	    FileInputFormat.addInputPath(incrstartjob, new Path(deltaStatic));
	    FileOutputFormat.setOutputPath(incrstartjob, new Path(output + "/" + iteration_id + "/iteration-0"));	//the filtered output dynamic data

	    incrstartjob.setFilterThreshold((float)filterthreshold);

	    incrstartjob.setIterativeMapperClass(PageRankMap.class);	
	    incrstartjob.setIterativeReducerClass(PageRankReduce.class);
	    incrstartjob.setProjectorClass(PageRankProjector.class);
	    
	    incrstartjob.setNumMapTasks(partitions);
	    incrstartjob.setNumReduceTasks(partitions);			

	    JobClient.runJob(incrstartjob);
	    
    	long incrend = System.currentTimeMillis();
    	long incrtime = (incrend - incrstart) / 1000;
    	Util.writeLog("incr.pagerank.log", "incremental start computation takes " + incrtime + " s");
	    
    	/**
    	 * the iterative incremental jobs
    	 */
	    long itertime = 0;
	    
    	long iterstart = System.currentTimeMillis();
    	
	    JobConf incriterjob = new JobConf(IncrPageRank.class);
	    jobname = "Incr PageRank Iterative Computation " + iterstart;
	    incriterjob.setJobName(jobname);
	    incriterjob.setLong(Parameters.ITER_START, iterstart);
	    
	    //set for iterative process   
	    incriterjob.setIncrementalIterative(true);
	    incriterjob.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
	    incriterjob.setMaxIterations(totaliter);					//max number of iterations

	    incriterjob.setStaticDataPath(updateStatic);				//the new static data
	    incriterjob.setPreserveStatePath(preserveState);		// the preserve map/reduce output path
	    incriterjob.setDynamicDataPath(output + "/" + iteration_id);				// the dynamic data path
	    incriterjob.setIncrOutputPath(output);
	    
	    incriterjob.setStaticInputFormat(SequenceFileInputFormat.class);
	    incriterjob.setDynamicInputFormat(SequenceFileInputFormat.class);		//MUST have this for the following jobs, even though the first job not need it
	    incriterjob.setResultInputFormat(SequenceFileInputFormat.class);		//if set termination check, you have to set this
    	incriterjob.setOutputFormat(SequenceFileOutputFormat.class);
	    
    	incriterjob.setStaticKeyClass(LongWritable.class);
    	incriterjob.setStaticValueClass(Text.class);
    	incriterjob.setOutputKeyClass(LongWritable.class);
    	incriterjob.setOutputValueClass(FloatWritable.class);
	    
	    FileInputFormat.addInputPath(incriterjob, new Path(updateStatic));
	    FileOutputFormat.setOutputPath(incriterjob, new Path(output + "/" + iteration_id + "/iter")); 	//the filtered output dynamic data

	    incriterjob.setFilterThreshold((float)filterthreshold);
	    incriterjob.setBufferReduceKVs(inmemreduce);

	    incriterjob.setIterativeMapperClass(PageRankMap.class);	
	    incriterjob.setIterativeReducerClass(PageRankReduce.class);
	    incriterjob.setProjectorClass(PageRankProjector.class);
	    
	    incriterjob.setNumMapTasks(partitions);
	    incriterjob.setNumReduceTasks(partitions);			

	    JobClient.runIterativeJob(incriterjob);

    	long iterend = System.currentTimeMillis();
    	itertime += (iterend - iterstart) / 1000;
    	Util.writeLog("incr.pagerank.log", "iteration computation takes " + itertime + " s");
    	
	    
	    return 0;
	}
}
```