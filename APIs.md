We list the main changes to the MapReduce framework.

## Basic Incremental Processing ##

For basic incremental processing, the only change is to change the input format.

#### Input Format ####

Input for original MapReduce is <K1,V1'>, where V1' is the updated value. Delta input for i2MapReduce is <K1,V1,+/->, where '+' represents the insertion of a new key value pair and '-' represents the deletion of an old key value pair.

## Iterative Processing ##

#### Input Format ####

Input for original MapReduce is <K1,V1>. The input for i2MapReduce is separated to structure data and state data. <SK,SV> is the structure key value pair. <DK,DV> is the state key value pair.

#### Projector Interface ####

Users should implement `Projector` interface.

```
import org.apache.hadoop.io.WritableComparable;

public interface Projector<SK extends WritableComparable, DK extends WritableComparable, DV> extends JobConfigurable{

	DK project(SK statickey);
	DV initDynamicV(DK dynamickey);
	Type getProjectType();

}
```


#### Mapper Interface ####

The original Mapper interface is changed

```
package org.apache.hadoop.mapred;

import java.io.IOException;

import org.apache.hadoop.io.Closeable;

public interface IterativeMapper<SK, SV, DK, DV, K2, V2> extends JobConfigurable, Closeable{
	void map(SK statickey, SV staticval, DK dynamickey, DV dynamicvalue, OutputCollector<K2, V2> output, Reporter reporter)
	  throws IOException;
}
```

## Incremental Processing ##

#### Input Format ####

Input for original MapReduce is <K1,V1'>, where V1' is the updated value. Structure delta input for i2MapReduce is <SK,SV,+/->, where '+' represents the insertion of a new key value pair and '-' represents the deletion of an old key value pair.

#### Change Propagation Control ####

`job.setFilterThreshold(threshold);`

`difference(DV_curr,DV_prev);`