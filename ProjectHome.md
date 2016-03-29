i2MapReduce is implemented based on Hadoop 1.0.3. It re-uses the saved states of
previous computations and performs re-computation only for states that
are affected by data changesThe key properties of i2MapReduce are listed as follows:

  * Fine-grained incremental processing (key-value pair level recomputation)
  * Optimized I/O to preserved state
  * Iterative processing support

i2MapReduce follows MapReduce programming paradigm, but a few APIs are extended for incrmental processing support. For batched MapReduce application, only the input format should be changed, and the main map and reduce programs can be re-used.

  * The input <K1,V1> is changed to <K1,V1,+/->. '+' or '-' is used to identify the input change.

For iterative MapReduce applications, there are two kinds of input: the background structure data and the initial state data to be updated iteratively. Let SK and SV represent iteration-invariant data and DK and DV represent iterated state data. You only need to implement a project function to specify there correlation.

  * `project(SK) -> DK`

In addition, the map interface is required to be changed.

  * `map(K1,K2)` is changed to `map(SK,SV,DK,DV)`

The detail of API introduction can be found in wikipage [APIs](APIs.md). A few **example applications** in the following are implemented. Please see the wiki page.

  * [APriori](APriori.md)
  * [PageRank](PageRank.md)
  * [Kmeans](Kmeans.md)
  * [GIMV](GIMV.md)

Ongoing work:

  * Integrate [SkewTune](https://code.google.com/p/skewtune/) into i2MapReduce for load balancing
  * Cost-aware execution plan that chooses the most suitable incremental processing techniques based on on-line cost analysis

The code is maintained in our own svn server. https://202.118.11.61:8443/svn/project/incr-hadoop-0.11/