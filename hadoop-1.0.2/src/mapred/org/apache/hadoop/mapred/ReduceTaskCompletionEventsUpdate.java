package org.apache.hadoop.mapred;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;

public class ReduceTaskCompletionEventsUpdate {
	
	ArrayList<Integer> completeTaskIDs;
	
	public ReduceTaskCompletionEventsUpdate() {};

	public ReduceTaskCompletionEventsUpdate(ArrayList<Integer> taskids) {
		completeTaskIDs = taskids;
	}
	
	public ArrayList<Integer> getCompleteTaskIDs(){
		return completeTaskIDs;
	}

	public void write(DataOutput out) throws IOException {
		out.writeInt(completeTaskIDs.size());
		for(int id : completeTaskIDs){
			out.writeInt(id);
		}
	}

	public void readFields(DataInput in) throws IOException {
		int size = in.readInt();
		completeTaskIDs.clear();
		for(int i=0; i<size; i++){
			completeTaskIDs.add(in.readInt());
		}
	}
}
