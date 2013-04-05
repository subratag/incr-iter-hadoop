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

public class IncrPageRank {

	//damping factor
	public static final float DAMPINGFAC = (float)0.8;
	public static final float RETAINFAC = (float)0.2;
	

	public static class PageRankMap extends MapReduceBase implements
		IterativeMapper<LongWritable, Text, LongWritable, FloatWritable, LongWritable, FloatWritable> {
	
		@Override
		public void map(LongWritable statickey, Text staticval,
				FloatWritable dynamicvalue,
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
	
		private long starttime;
		private int iteration;
		
		@Override
		public void configure(JobConf job){
			starttime = job.getLong("starttime", 0);
			iteration = 0;
			//System.out.println("start new");
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
				
				//System.out.println("reduce on " + key + " with " + v);
				i++;
				rank += v;
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
		public void close(){
			iteration++;
			System.out.println("iteration " + iteration + " now " + System.currentTimeMillis() + " past " + (System.currentTimeMillis()-starttime));
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
				"<partitions> <filterthreshold> <totaliter>");
	}

	public static int main(String[] args) throws Exception {
		if (args.length < 8) {
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
	    incrstartjob.setDynamicDataPath(convValue);				// the stable dynamic data path
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
	    FileOutputFormat.setOutputPath(incrstartjob, new Path(output + "/" + iteration_id + "/start"));	//the filtered output dynamic data

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
	    boolean cont = true;
	    
	    long itertime = 0;
	    
    	long iterstart = System.currentTimeMillis();
    	
	    JobConf incriterjob = new JobConf(IncrPageRank.class);
	    jobname = "Incr PageRank Iterative Computation " + iterstart;
	    incriterjob.setJobName(jobname);
    
	    incriterjob.setLong("starttime", iterstart);
	    //if(partitions == 0) partitions = Util.getTTNum(job);
	    
	    //set for iterative process   
	    incriterjob.setIncrementalIterative(true);
	    incriterjob.setIterativeAlgorithmID(iteration_id);		//must be unique for an iterative algorithm
	    incriterjob.setMaxIterations(totaliter);					//max number of iterations

	    incriterjob.setStaticDataPath(updateStatic);				//the new static data
	    incriterjob.setPreserveStatePath(preserveState);		// the preserve map/reduce output path
	    incriterjob.setDynamicDataPath(output + "/" + iteration_id + "/start");				// the dynamic data path
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

	    incriterjob.setIterativeMapperClass(PageRankMap.class);	
	    incriterjob.setIterativeReducerClass(PageRankReduce.class);
	    incriterjob.setProjectorClass(PageRankProjector.class);
	    
	    incriterjob.setNumMapTasks(partitions);
	    incriterjob.setNumReduceTasks(partitions);			

	    cont = JobClient.runIterativeJob(incriterjob);

    	long iterend = System.currentTimeMillis();
    	itertime += (iterend - iterstart) / 1000;
    	Util.writeLog("incr.pagerank.log", "iteration computation takes " + itertime + " s");
    	
    	iteration++;
	    
	    return 0;
	}
}
