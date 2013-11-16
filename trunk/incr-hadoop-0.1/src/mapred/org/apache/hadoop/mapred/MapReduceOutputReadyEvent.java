package org.apache.hadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.apache.hadoop.io.Writable;

public class MapReduceOutputReadyEvent implements Writable {

	boolean mapReady;
	boolean reduceReady;
	
	public MapReduceOutputReadyEvent(){ }
	
	public MapReduceOutputReadyEvent(boolean mapReady, boolean reduceReady){
		this.mapReady = mapReady;
		this.reduceReady = reduceReady;
	}
	
	public boolean isMapOutputReady(){
		return mapReady;
	}
	
	public void setMapOutputReady(boolean ready){
		mapReady = ready;
	}
	
	public boolean isReduceOutputReady(){
		return reduceReady;
	}
	
	public void setReduceOutputReady(boolean ready){
		reduceReady = ready;
	}
	
	@Override
	public void write(DataOutput out) throws IOException {
		out.writeBoolean(mapReady);
		out.writeBoolean(reduceReady);
	}

	@Override
	public void readFields(DataInput in) throws IOException {
		this.mapReady = in.readBoolean();
		this.reduceReady = in.readBoolean();
	}

}
