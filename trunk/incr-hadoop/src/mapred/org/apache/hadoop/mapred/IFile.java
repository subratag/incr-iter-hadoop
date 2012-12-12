/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FSDataInputStream;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.DataInputBuffer;
import org.apache.hadoop.io.DataOutputBuffer;
import org.apache.hadoop.io.WritableUtils;
import org.apache.hadoop.io.compress.CodecPool;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionOutputStream;
import org.apache.hadoop.io.compress.Compressor;
import org.apache.hadoop.io.compress.Decompressor;
import org.apache.hadoop.io.serializer.SerializationFactory;
import org.apache.hadoop.io.serializer.Serializer;
import org.mortbay.log.Log;

/**
 * <code>IFile</code> is the simple <key-len, value-len, key, value> format
 * for the intermediate map-outputs in Map-Reduce.
 * 
 * There is a <code>Writer</code> to write out map-outputs in this format and 
 * a <code>Reader</code> to read files of this format.
 */
public class IFile {

  private static final int EOF_MARKER = -1;
  
  /**
   * <code>IFile.Writer</code> to write out intermediate map-outputs. 
   */
  public static class Writer<K extends Object, V extends Object> {
    FSDataOutputStream out;
    boolean ownOutputStream = false;
    long start = 0;
    FSDataOutputStream rawOut;
    
    CompressionOutputStream compressedOut;
    Compressor compressor;
    boolean compressOutput = false;
    
    long decompressedBytesWritten = 0;
    long compressedBytesWritten = 0;

    // Count records written to disk
    private long numRecordsWritten = 0;
    private final Counters.Counter writtenRecordsCounter;

    IFileOutputStream checksumOut;

    Class<K> keyClass;
    Class<V> valueClass;
    Serializer<K> keySerializer;
    Serializer<V> valueSerializer;
    
    DataOutputBuffer buffer = new DataOutputBuffer();

    public Writer(Configuration conf, FileSystem fs, Path file, 
                  Class<K> keyClass, Class<V> valueClass,
                  CompressionCodec codec,
                  Counters.Counter writesCounter) throws IOException {
      this(conf, fs.create(file), keyClass, valueClass, codec,
           writesCounter);
      ownOutputStream = true;
    }
    
    public Writer(Configuration conf, FSDataOutputStream out, 
        Class<K> keyClass, Class<V> valueClass,
        CompressionCodec codec, Counters.Counter writesCounter)
        throws IOException {
      this.writtenRecordsCounter = writesCounter;
      this.checksumOut = new IFileOutputStream(out);
      this.rawOut = out;
      this.start = this.rawOut.getPos();
      
      if (codec != null) {
        this.compressor = CodecPool.getCompressor(codec);
        this.compressor.reset();
        this.compressedOut = codec.createOutputStream(checksumOut, compressor);
        this.out = new FSDataOutputStream(this.compressedOut,  null);
        this.compressOutput = true;
      } else {
        this.out = new FSDataOutputStream(checksumOut,null);
      }
      
      this.keyClass = keyClass;
      this.valueClass = valueClass;
      SerializationFactory serializationFactory = new SerializationFactory(conf);
      this.keySerializer = serializationFactory.getSerializer(keyClass);
      this.keySerializer.open(buffer);
      this.valueSerializer = serializationFactory.getSerializer(valueClass);
      this.valueSerializer.open(buffer);
    }

    public void close() throws IOException {

      // Close the serializers
      keySerializer.close();
      valueSerializer.close();

      // Write EOF_MARKER for key/value length
      WritableUtils.writeVInt(out, EOF_MARKER);
      WritableUtils.writeVInt(out, EOF_MARKER);
      decompressedBytesWritten += 2 * WritableUtils.getVIntSize(EOF_MARKER);
      
      //Flush the stream
      out.flush();
  
      if (compressOutput) {
        // Flush
        compressedOut.finish();
        compressedOut.resetState();
      }
      
      // Close the underlying stream iff we own it...
      if (ownOutputStream) {
        out.close();
      }
      else {
        // Write the checksum
        checksumOut.finish();
      }

      compressedBytesWritten = rawOut.getPos() - start;

      if (compressOutput) {
        // Return back the compressor
        CodecPool.returnCompressor(compressor);
        compressor = null;
      }

      out = null;
      if(writtenRecordsCounter != null) {
        writtenRecordsCounter.increment(numRecordsWritten);
      }
    }

    public void append(K key, V value) throws IOException {
      if (key.getClass() != keyClass)
        throw new IOException("wrong key class: "+ key.getClass()
                              +" is not "+ keyClass);
      if (value.getClass() != valueClass)
        throw new IOException("wrong value class: "+ value.getClass()
                              +" is not "+ valueClass);

      // Append the 'key'
      keySerializer.serialize(key);
      int keyLength = buffer.getLength();
      if (keyLength < 0) {
        throw new IOException("Negative key-length not allowed: " + keyLength + 
                              " for " + key);
      }

      // Append the 'value'
      valueSerializer.serialize(value);
      int valueLength = buffer.getLength() - keyLength;
      if (valueLength < 0) {
        throw new IOException("Negative value-length not allowed: " + 
                              valueLength + " for " + value);
      }
      
      // Write the record out
      WritableUtils.writeVInt(out, keyLength);                  // key length
      WritableUtils.writeVInt(out, valueLength);                // value length

      /********************************
       * same key length and same value length, need optimize later
       */
      
      //Log.info("key length : " + keyLength + " value length : " + valueLength);
      out.write(buffer.getData(), 0, buffer.getLength());       // data

      // Reset
      buffer.reset();
      
      // Update bytes written
      decompressedBytesWritten += keyLength + valueLength + 
                                  WritableUtils.getVIntSize(keyLength) + 
                                  WritableUtils.getVIntSize(valueLength);
      ++numRecordsWritten;
    }
    
    public void append(DataInputBuffer key, DataInputBuffer value)
    throws IOException {
      int keyLength = key.getLength() - key.getPosition();
      if (keyLength < 0) {
        throw new IOException("Negative key-length not allowed: " + keyLength + 
                              " for " + key);
      }
      
      int valueLength = value.getLength() - value.getPosition();
      if (valueLength < 0) {
        throw new IOException("Negative value-length not allowed: " + 
                              valueLength + " for " + value);
      }

      WritableUtils.writeVInt(out, keyLength);
      WritableUtils.writeVInt(out, valueLength);
      out.write(key.getData(), key.getPosition(), keyLength); 
      out.write(value.getData(), value.getPosition(), valueLength); 

      // Update bytes written
      decompressedBytesWritten += keyLength + valueLength + 
                      WritableUtils.getVIntSize(keyLength) + 
                      WritableUtils.getVIntSize(valueLength);
      ++numRecordsWritten;
    }
    
    public long getRawLength() {
      return decompressedBytesWritten;
    }
    
    public long getCompressedLength() {
      return compressedBytesWritten;
    }
  }

  /**
   * <code>IFile.SKVWriter</code> to write out intermediate map-outputs. 
   */
  public static class TrippleWriter<T1 extends Object, T2 extends Object, T3 extends Object> {
    FSDataOutputStream out;
    boolean ownOutputStream = false;
    long start = 0;
    FSDataOutputStream rawOut;
    
    CompressionOutputStream compressedOut;
    Compressor compressor;
    boolean compressOutput = false;
    
    long decompressedBytesWritten = 0;
    long compressedBytesWritten = 0;

    // Count records written to disk
    private long numRecordsWritten = 0;
    private final Counters.Counter writtenRecordsCounter;

    IFileOutputStream checksumOut;

    Class<T1> t1Class;
    Class<T2> t2Class;
    Class<T3> t3Class;
    Serializer<T1> t1Serializer;
    Serializer<T2> t2Serializer;
    Serializer<T3> t3Serializer;
    
    DataOutputBuffer buffer = new DataOutputBuffer();

    public TrippleWriter(Configuration conf, FileSystem fs, Path file, 
    				Class<T1> t1Class, Class<T2> t2Class, Class<T3> t3Class,
                  CompressionCodec codec,
                  Counters.Counter writesCounter) throws IOException {
      this(conf, fs.create(file), t1Class, t2Class, t3Class, codec,
           writesCounter);
      ownOutputStream = true;
    }
    
    public TrippleWriter(Configuration conf, FSDataOutputStream out, 
    		Class<T1> t1Class, Class<T2> t2Class, Class<T3> t3Class,
        CompressionCodec codec, Counters.Counter writesCounter)
        throws IOException {
      this.writtenRecordsCounter = writesCounter;
      this.checksumOut = new IFileOutputStream(out);
      this.rawOut = out;
      this.start = this.rawOut.getPos();
      
      if (codec != null) {
        this.compressor = CodecPool.getCompressor(codec);
        this.compressor.reset();
        this.compressedOut = codec.createOutputStream(checksumOut, compressor);
        this.out = new FSDataOutputStream(this.compressedOut,  null);
        this.compressOutput = true;
      } else {
        this.out = new FSDataOutputStream(checksumOut,null);
      }
      
      this.t1Class = t1Class;
      this.t2Class = t2Class;
      this.t3Class = t3Class;
      SerializationFactory serializationFactory = new SerializationFactory(conf);
      this.t1Serializer = serializationFactory.getSerializer(t1Class);
      this.t1Serializer.open(buffer);
      this.t2Serializer = serializationFactory.getSerializer(t2Class);
      this.t2Serializer.open(buffer);
      this.t3Serializer = serializationFactory.getSerializer(t3Class);
      this.t3Serializer.open(buffer);
    }

    public void close() throws IOException {

      // Close the serializers
      t1Serializer.close();
      t2Serializer.close();
      t3Serializer.close();

      // Write EOF_MARKER for key/value length
      WritableUtils.writeVInt(out, EOF_MARKER);
      WritableUtils.writeVInt(out, EOF_MARKER);
      WritableUtils.writeVInt(out, EOF_MARKER);
      decompressedBytesWritten += 3 * WritableUtils.getVIntSize(EOF_MARKER);
      
      //Flush the stream
      out.flush();
  
      if (compressOutput) {
        // Flush
        compressedOut.finish();
        compressedOut.resetState();
      }
      
      // Close the underlying stream iff we own it...
      if (ownOutputStream) {
        out.close();
      }
      else {
        // Write the checksum
        checksumOut.finish();
      }

      compressedBytesWritten = rawOut.getPos() - start;

      if (compressOutput) {
        // Return back the compressor
        CodecPool.returnCompressor(compressor);
        compressor = null;
      }

      out = null;
      if(writtenRecordsCounter != null) {
        writtenRecordsCounter.increment(numRecordsWritten);
      }
    }

    public void append(T1 t1, T2 t2, T3 t3) throws IOException {
        if (t1.getClass() != t1Class)
            throw new IOException("wrong t1 class: "+ t1.getClass()
                                  +" is not "+ t1Class);
      if (t2.getClass() != t2Class)
        throw new IOException("wrong t2 class: "+ t2.getClass()
                              +" is not "+ t2Class);
      if (t3.getClass() != t3Class)
        throw new IOException("wrong t3 class: "+ t3.getClass()
                              +" is not "+ t3Class);

      // Append the 'skey'
      t1Serializer.serialize(t1);
      int t1Length = buffer.getLength();
      if (t1Length < 0) {
        throw new IOException("Negative key-length not allowed: " + t1Length + 
                              " for " + t1);
      }
      
      // Append the 'key'
      t2Serializer.serialize(t2);
      int t2Length = buffer.getLength() - t1Length;
      if (t2Length < 0) {
        throw new IOException("Negative t2-length not allowed: " + t2Length + 
                              " for " + t2);
      }

      // Append the 'value'
      t3Serializer.serialize(t3);
      int t3Length = buffer.getLength() - t2Length - t1Length;
      if (t3Length < 0) {
        throw new IOException("Negative t3-length not allowed: " + 
                              t3Length + " for " + t3);
      }
      
      // Write the record out
      WritableUtils.writeVInt(out, t1Length);                  // key length
      WritableUtils.writeVInt(out, t2Length);                  // key length
      WritableUtils.writeVInt(out, t3Length);                // value length

      /********************************
       * same key length and same value length, need optimize later
       */
      
      //Log.info("key length : " + keyLength + " value length : " + valueLength);
      out.write(buffer.getData(), 0, buffer.getLength());       // data

      // Reset
      buffer.reset();
      
      // Update bytes written
      decompressedBytesWritten += t1Length + t2Length + t3Length + 
    		  					  WritableUtils.getVIntSize(t1Length) + 
                                  WritableUtils.getVIntSize(t2Length) + 
                                  WritableUtils.getVIntSize(t3Length);
      ++numRecordsWritten;
    }
    
    public void append(DataInputBuffer t1, DataInputBuffer t2, DataInputBuffer t3)
    throws IOException {
      int t1Length = t1.getLength() - t1.getPosition();
      if (t1Length < 0) {
        throw new IOException("Negative t1-length not allowed: " + t1Length + 
                              " for " + t1);
      }
      
      int t2Length = t2.getLength() - t2.getPosition();
      if (t2Length < 0) {
        throw new IOException("Negative t2-length not allowed: " + 
                              t2Length + " for " + t2);
      }

      int t3Length = t3.getLength() - t3.getPosition();
      if (t3Length < 0) {
        throw new IOException("Negative t3-length not allowed: " + 
                              t2Length + " for " + t3);
      }
      
      WritableUtils.writeVInt(out, t1Length);
      WritableUtils.writeVInt(out, t2Length);
      WritableUtils.writeVInt(out, t3Length);
      out.write(t1.getData(), t1.getPosition(), t1Length); 
      out.write(t2.getData(), t2.getPosition(), t2Length); 
      out.write(t3.getData(), t3.getPosition(), t3Length); 

      // Update bytes written
      decompressedBytesWritten += t1Length + t2Length + t3Length + 
                      WritableUtils.getVIntSize(t1Length) + 
                      WritableUtils.getVIntSize(t2Length) + 
                      WritableUtils.getVIntSize(t3Length);
      ++numRecordsWritten;
    }
    
    public long getRawLength() {
      return decompressedBytesWritten;
    }
    
    public long getCompressedLength() {
      return compressedBytesWritten;
    }
  }
  
  /**
   * <code>IFile.Writer</code> to write out intermediate map-outputs. 
   */
  public static class FixedLengthWriter<K extends Object, V extends Object> {
    FSDataOutputStream out;
    boolean ownOutputStream = false;
    long start = 0;
    FSDataOutputStream rawOut;
    boolean first = true;		//record the k,v length
    int keyLength = 0;
    int valueLength;
    
    CompressionOutputStream compressedOut;
    Compressor compressor;
    boolean compressOutput = false;
    
    long decompressedBytesWritten = 0;
    long compressedBytesWritten = 0;

    // Count records written to disk
    private long numRecordsWritten = 0;
    private final Counters.Counter writtenRecordsCounter;

    IFileOutputStream checksumOut;

    Class<K> keyClass;
    Class<V> valueClass;
    Serializer<K> keySerializer;
    Serializer<V> valueSerializer;
    
    DataOutputBuffer buffer = new DataOutputBuffer();

    public FixedLengthWriter(Configuration conf, FileSystem fs, Path file, 
                  Class<K> keyClass, Class<V> valueClass,
                  CompressionCodec codec,
                  Counters.Counter writesCounter) throws IOException {
      this(conf, fs.create(file), keyClass, valueClass, codec,
           writesCounter);
      ownOutputStream = true;
    }
    
    public FixedLengthWriter(Configuration conf, FSDataOutputStream out, 
        Class<K> keyClass, Class<V> valueClass,
        CompressionCodec codec, Counters.Counter writesCounter)
        throws IOException {
      this.writtenRecordsCounter = writesCounter;
      this.checksumOut = new IFileOutputStream(out);
      this.rawOut = out;
      this.start = this.rawOut.getPos();
      
      if (codec != null) {
        this.compressor = CodecPool.getCompressor(codec);
        this.compressor.reset();
        this.compressedOut = codec.createOutputStream(checksumOut, compressor);
        this.out = new FSDataOutputStream(this.compressedOut,  null);
        this.compressOutput = true;
      } else {
        this.out = new FSDataOutputStream(checksumOut,null);
      }
      
      this.keyClass = keyClass;
      this.valueClass = valueClass;
      SerializationFactory serializationFactory = new SerializationFactory(conf);
      this.keySerializer = serializationFactory.getSerializer(keyClass);
      this.keySerializer.open(buffer);
      this.valueSerializer = serializationFactory.getSerializer(valueClass);
      this.valueSerializer.open(buffer);
    }

    public void close() throws IOException {

      // Close the serializers
      keySerializer.close();
      valueSerializer.close();

      // Write EOF_MARKER for key/value length
      WritableUtils.writeVInt(out, EOF_MARKER);
      WritableUtils.writeVInt(out, EOF_MARKER);
      decompressedBytesWritten += 2 * WritableUtils.getVIntSize(EOF_MARKER);
      
      //Flush the stream
      out.flush();
  
      if (compressOutput) {
        // Flush
        compressedOut.finish();
        compressedOut.resetState();
      }
      
      // Close the underlying stream iff we own it...
      if (ownOutputStream) {
        out.close();
      }
      else {
        // Write the checksum
        checksumOut.finish();
      }

      compressedBytesWritten = rawOut.getPos() - start;

      if (compressOutput) {
        // Return back the compressor
        CodecPool.returnCompressor(compressor);
        compressor = null;
      }

      out = null;
      if(writtenRecordsCounter != null) {
        writtenRecordsCounter.increment(numRecordsWritten);
      }
    }

    public void append(K key, V value) throws IOException {
      if (key.getClass() != keyClass)
        throw new IOException("wrong key class: "+ key.getClass()
                              +" is not "+ keyClass);
      if (value.getClass() != valueClass)
        throw new IOException("wrong value class: "+ value.getClass()
                              +" is not "+ valueClass);

      if(first){
          // Append the 'key'
          keySerializer.serialize(key);

    	  keyLength = buffer.getLength();
          if (keyLength < 0) {
            throw new IOException("Negative key-length not allowed: " + keyLength + 
                                  " for " + key);
          }
          
          // Append the 'value'
          valueSerializer.serialize(value);
    	  valueLength = buffer.getLength() - keyLength;
    	  if (valueLength < 0) {
    	        throw new IOException("Negative value-length not allowed: " + 
    	                              valueLength + " for " + value);
	      }

          // Write the record out
          WritableUtils.writeVInt(out, keyLength);                  // key length
          WritableUtils.writeVInt(out, valueLength);                // value length
          
          // Update bytes written
          decompressedBytesWritten += WritableUtils.getVIntSize(keyLength) + 
                                      WritableUtils.getVIntSize(valueLength);
          
          first = false;
      }else{
    	  keySerializer.serialize(key);
    	  valueSerializer.serialize(value);
      }

      /********************************
       * same key length and same value length, need optimize later
       */
      
      //Log.info("key length : " + keyLength + " value length : " + valueLength);
      out.write(buffer.getData(), 0, buffer.getLength());       // data

      // Reset
      buffer.reset();
      
      // Update bytes written
      decompressedBytesWritten += keyLength + valueLength;
      ++numRecordsWritten;
    }
    
    public void append(DataInputBuffer key, DataInputBuffer value)
    throws IOException {
      int keyLength = key.getLength() - key.getPosition();
      if (keyLength < 0) {
        throw new IOException("Negative key-length not allowed: " + keyLength + 
                              " for " + key);
      }
      
      int valueLength = value.getLength() - value.getPosition();
      if (valueLength < 0) {
        throw new IOException("Negative value-length not allowed: " + 
                              valueLength + " for " + value);
      }

      WritableUtils.writeVInt(out, keyLength);
      WritableUtils.writeVInt(out, valueLength);
      out.write(key.getData(), key.getPosition(), keyLength); 
      out.write(value.getData(), value.getPosition(), valueLength); 

      // Update bytes written
      decompressedBytesWritten += keyLength + valueLength + 
                      WritableUtils.getVIntSize(keyLength) + 
                      WritableUtils.getVIntSize(valueLength);
      ++numRecordsWritten;
    }
    
    public long getRawLength() {
      return decompressedBytesWritten;
    }
    
    public long getCompressedLength() {
      return compressedBytesWritten;
    }
  }
  
  /**
   * <code>IFile.Reader</code> to read intermediate map-outputs. 
   */
  public static class Reader<K extends Object, V extends Object> {
    private static final int DEFAULT_BUFFER_SIZE = 128*1024;
    private static final int MAX_VINT_SIZE = 9;

    // Count records read from disk
    private long numRecordsRead = 0;
    private final Counters.Counter readRecordsCounter;

    final InputStream in;        // Possibly decompressed stream that we read
    Decompressor decompressor;
    long bytesRead = 0;
    final long fileLength;
    boolean eof = false;
    final IFileInputStream checksumIn;
    
    byte[] buffer = null;
    int bufferSize = DEFAULT_BUFFER_SIZE;
    DataInputBuffer dataIn = new DataInputBuffer();

    int recNo = 1;
    
    /**
     * Construct an IFile Reader.
     * 
     * @param conf Configuration File 
     * @param fs  FileSystem
     * @param file Path of the file to be opened. This file should have
     *             checksum bytes for the data at the end of the file.
     * @param codec codec
     * @param readsCounter Counter for records read from disk
     * @throws IOException
     */
    public Reader(Configuration conf, FileSystem fs, Path file,
                  CompressionCodec codec,
                  Counters.Counter readsCounter) throws IOException {
      this(conf, fs.open(file), 
           fs.getFileStatus(file).getLen(),
           codec, readsCounter);
    }

    /**
     * Construct an IFile Reader.
     * 
     * @param conf Configuration File 
     * @param in   The input stream
     * @param length Length of the data in the stream, including the checksum
     *               bytes.
     * @param codec codec
     * @param readsCounter Counter for records read from disk
     * @throws IOException
     */
    public Reader(Configuration conf, FSDataInputStream in, long length, 
                  CompressionCodec codec,
                  Counters.Counter readsCounter) throws IOException {
      readRecordsCounter = readsCounter;
      checksumIn = new IFileInputStream(in,length);
      if (codec != null) {
        decompressor = CodecPool.getDecompressor(codec);
        this.in = codec.createInputStream(checksumIn, decompressor);
      } else {
        this.in = checksumIn;
      }
      this.fileLength = length;
      
      if (conf != null) {
        bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
      }
    }
    
    public long getLength() { 
      return fileLength - checksumIn.getSize();
    }
    
    public long getPosition() throws IOException {    
      return checksumIn.getPosition(); 
    }
    
    /**
     * Read upto len bytes into buf starting at offset off.
     * 
     * @param buf buffer 
     * @param off offset
     * @param len length of buffer
     * @return the no. of bytes read
     * @throws IOException
     */
    private int readData(byte[] buf, int off, int len) throws IOException {
      int bytesRead = 0;
      while (bytesRead < len) {
        int n = in.read(buf, off+bytesRead, len-bytesRead);
        if (n < 0) {
          return bytesRead;
        }
        bytesRead += n;
      }
      return len;
    }
    
    void readNextBlock(int minSize) throws IOException {
      if (buffer == null) {
        buffer = new byte[bufferSize];
        dataIn.reset(buffer, 0, 0);
      }
      buffer = 
        rejigData(buffer, 
                  (bufferSize < minSize) ? new byte[minSize << 1] : buffer);
      bufferSize = buffer.length;
    }
    
    private byte[] rejigData(byte[] source, byte[] destination) 
    throws IOException{
      // Copy remaining data into the destination array
      int bytesRemaining = dataIn.getLength()-dataIn.getPosition();
      if (bytesRemaining > 0) {
        System.arraycopy(source, dataIn.getPosition(), 
            destination, 0, bytesRemaining);
      }
      
      // Read as much data as will fit from the underlying stream 
      int n = readData(destination, bytesRemaining, 
                       (destination.length - bytesRemaining));
      dataIn.reset(destination, 0, (bytesRemaining + n));
      
      return destination;
    }
    
    public boolean next(DataInputBuffer key, DataInputBuffer value) 
    throws IOException {
      // Sanity check
      if (eof) {
        throw new EOFException("Completed reading " + bytesRead);
      }
      
      // Check if we have enough data to read lengths
      if ((dataIn.getLength() - dataIn.getPosition()) < 2*MAX_VINT_SIZE) {
        readNextBlock(2*MAX_VINT_SIZE);
      }
      
      // Read key and value lengths
      int oldPos = dataIn.getPosition();
      int keyLength = WritableUtils.readVInt(dataIn);
      int valueLength = WritableUtils.readVInt(dataIn);
      int pos = dataIn.getPosition();
      bytesRead += pos - oldPos;
      
      // Check for EOF
      if (keyLength == EOF_MARKER && valueLength == EOF_MARKER) {
        eof = true;
        return false;
      }
      
      // Sanity check
      if (keyLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
                              keyLength);
      }
      if (valueLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative value-length: " + 
                              valueLength);
      }
      
      final int recordLength = keyLength + valueLength;
      
      // Check if we have the raw key/value in the buffer
      if ((dataIn.getLength()-pos) < recordLength) {
        readNextBlock(recordLength);
        
        // Sanity check
        if ((dataIn.getLength() - dataIn.getPosition()) < recordLength) {
          throw new EOFException("Rec# " + recNo + ": Could read the next " +
          		                   " record");
        }
      }

      // Setup the key and value
      pos = dataIn.getPosition();
      byte[] data = dataIn.getData();
      key.reset(data, pos, keyLength);
      value.reset(data, (pos + keyLength), valueLength);
      
      // Position for the next record
      long skipped = dataIn.skip(recordLength);
      if (skipped != recordLength) {
        throw new IOException("Rec# " + recNo + ": Failed to skip past record " +
        		                  "of length: " + recordLength);
      }
      
      // Record the bytes read
      bytesRead += recordLength;

      ++recNo;
      ++numRecordsRead;

      return true;
    }

    public void close() throws IOException {
      // Return the decompressor
      if (decompressor != null) {
        decompressor.reset();
        CodecPool.returnDecompressor(decompressor);
        decompressor = null;
      }
      
      // Close the underlying stream
      in.close();
      
      // Release the buffer
      dataIn = null;
      buffer = null;
      if(readRecordsCounter != null) {
        readRecordsCounter.increment(numRecordsRead);
      }
    }
  }    
  
  /**
   * <code>IFile.SKVReader</code> to read intermediate map-outputs. 
   */
  public static class TrippleReader<T1 extends Object, T2 extends Object, T3 extends Object> {
    private static final int DEFAULT_BUFFER_SIZE = 128*1024;
    private static final int MAX_VINT_SIZE = 9;

    // Count records read from disk
    private long numRecordsRead = 0;
    private final Counters.Counter readRecordsCounter;

    final InputStream in;        // Possibly decompressed stream that we read
    Decompressor decompressor;
    long bytesRead = 0;
    final long fileLength;
    boolean eof = false;
    final IFileInputStream checksumIn;
    
    byte[] buffer = null;
    int bufferSize = DEFAULT_BUFFER_SIZE;
    DataInputBuffer dataIn = new DataInputBuffer();

    int recNo = 1;
    
    /**
     * Construct an IFile Reader.
     * 
     * @param conf Configuration File 
     * @param fs  FileSystem
     * @param file Path of the file to be opened. This file should have
     *             checksum bytes for the data at the end of the file.
     * @param codec codec
     * @param readsCounter Counter for records read from disk
     * @throws IOException
     */
    public TrippleReader(Configuration conf, FileSystem fs, Path file,
                  CompressionCodec codec,
                  Counters.Counter readsCounter) throws IOException {
      this(conf, fs.open(file), 
           fs.getFileStatus(file).getLen(),
           codec, readsCounter);
    }

    /**
     * Construct an IFile Reader.
     * 
     * @param conf Configuration File 
     * @param in   The input stream
     * @param length Length of the data in the stream, including the checksum
     *               bytes.
     * @param codec codec
     * @param readsCounter Counter for records read from disk
     * @throws IOException
     */
    public TrippleReader(Configuration conf, FSDataInputStream in, long length, 
                  CompressionCodec codec,
                  Counters.Counter readsCounter) throws IOException {
      readRecordsCounter = readsCounter;
      checksumIn = new IFileInputStream(in,length);
      if (codec != null) {
        decompressor = CodecPool.getDecompressor(codec);
        this.in = codec.createInputStream(checksumIn, decompressor);
      } else {
        this.in = checksumIn;
      }
      this.fileLength = length;
      
      if (conf != null) {
        bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
      }
    }
    
    public long getLength() { 
      return fileLength - checksumIn.getSize();
    }
    
    public long getPosition() throws IOException {    
      return checksumIn.getPosition(); 
    }
    
    /**
     * Read upto len bytes into buf starting at offset off.
     * 
     * @param buf buffer 
     * @param off offset
     * @param len length of buffer
     * @return the no. of bytes read
     * @throws IOException
     */
    private int readData(byte[] buf, int off, int len) throws IOException {
      int bytesRead = 0;
      while (bytesRead < len) {
        int n = in.read(buf, off+bytesRead, len-bytesRead);
        if (n < 0) {
          return bytesRead;
        }
        bytesRead += n;
      }
      return len;
    }
    
    void readNextBlock(int minSize) throws IOException {
      if (buffer == null) {
        buffer = new byte[bufferSize];
        dataIn.reset(buffer, 0, 0);
      }
      buffer = 
        rejigData(buffer, 
                  (bufferSize < minSize) ? new byte[minSize << 1] : buffer);
      bufferSize = buffer.length;
    }
    
    private byte[] rejigData(byte[] source, byte[] destination) 
    throws IOException{
      // Copy remaining data into the destination array
      int bytesRemaining = dataIn.getLength()-dataIn.getPosition();
      if (bytesRemaining > 0) {
        System.arraycopy(source, dataIn.getPosition(), 
            destination, 0, bytesRemaining);
      }
      
      // Read as much data as will fit from the underlying stream 
      int n = readData(destination, bytesRemaining, 
                       (destination.length - bytesRemaining));
      dataIn.reset(destination, 0, (bytesRemaining + n));
      
      return destination;
    }
    
    public boolean next(DataInputBuffer t1, DataInputBuffer t2, DataInputBuffer t3) 
    throws IOException {
      // Sanity check
      if (eof) {
        throw new EOFException("Completed reading " + bytesRead);
      }
      
      // Check if we have enough data to read lengths
      if ((dataIn.getLength() - dataIn.getPosition()) < 3*MAX_VINT_SIZE) {
        readNextBlock(3*MAX_VINT_SIZE);
      }
      
      // Read key and value lengths
      int oldPos = dataIn.getPosition();
      int t1Length = WritableUtils.readVInt(dataIn);
      int t2Length = WritableUtils.readVInt(dataIn);
      int t3Length = WritableUtils.readVInt(dataIn);
      int pos = dataIn.getPosition();
      bytesRead += pos - oldPos;
      
      // Check for EOF
      if (t1Length == EOF_MARKER && t2Length == EOF_MARKER && t3Length == EOF_MARKER) {
        eof = true;
        return false;
      }
      
      // Sanity check
      if (t1Length < 0) {
          throw new IOException("Rec# " + recNo + ": Negative t1-length: " + 
                                t1Length);
      }
      if (t2Length < 0) {
        throw new IOException("Rec# " + recNo + ": Negative t2-length: " + 
                              t2Length);
      }
      if (t3Length < 0) {
        throw new IOException("Rec# " + recNo + ": Negative t3-length: " + 
                              t3Length);
      }
      
      final int recordLength = t1Length + t2Length + t3Length;
      
      // Check if we have the raw key/value in the buffer
      if ((dataIn.getLength()-pos) < recordLength) {
        readNextBlock(recordLength);
        
        // Sanity check
        if ((dataIn.getLength() - dataIn.getPosition()) < recordLength) {
          throw new EOFException("Rec# " + recNo + ": Could read the next " +
          		                   " record");
        }
      }

      // Setup the key and value
      pos = dataIn.getPosition();
      byte[] data = dataIn.getData();
      t1.reset(data, pos, t1Length);
      t2.reset(data, (pos + t1Length), t2Length);
      t3.reset(data, (pos + t1Length + t2Length), t3Length);
      
      // Position for the next record
      long skipped = dataIn.skip(recordLength);
      if (skipped != recordLength) {
        throw new IOException("Rec# " + recNo + ": Failed to skip past record " +
        		                  "of length: " + recordLength);
      }
      
      // Record the bytes read
      bytesRead += recordLength;

      ++recNo;
      ++numRecordsRead;

      return true;
    }

    public void close() throws IOException {
      // Return the decompressor
      if (decompressor != null) {
        decompressor.reset();
        CodecPool.returnDecompressor(decompressor);
        decompressor = null;
      }
      
      // Close the underlying stream
      in.close();
      
      // Release the buffer
      dataIn = null;
      buffer = null;
      if(readRecordsCounter != null) {
        readRecordsCounter.increment(numRecordsRead);
      }
    }
  } 
  
  /**
   * <code>IFile.Reader</code> to read intermediate map-outputs. 
   */
  public static class FixedLengthReader<K extends Object, V extends Object> {
    private static final int DEFAULT_BUFFER_SIZE = 128*1024;
    private static final int MAX_VINT_SIZE = 9;

    // Count records read from disk
    private long numRecordsRead = 0;
    private final Counters.Counter readRecordsCounter;

    final InputStream in;        // Possibly decompressed stream that we read
    Decompressor decompressor;
    long bytesRead = 0;
    final long fileLength;
    boolean eof = false;
    final IFileInputStream checksumIn;
    
    byte[] buffer = null;
    int bufferSize = DEFAULT_BUFFER_SIZE;
    DataInputBuffer dataIn = new DataInputBuffer();

    int recNo = 1;
    
    boolean first = true;
    int keyLength;
    int valueLength;
    
    /**
     * Construct an IFile Reader.
     * 
     * @param conf Configuration File 
     * @param fs  FileSystem
     * @param file Path of the file to be opened. This file should have
     *             checksum bytes for the data at the end of the file.
     * @param codec codec
     * @param readsCounter Counter for records read from disk
     * @throws IOException
     */
    public FixedLengthReader(Configuration conf, FileSystem fs, Path file,
                  CompressionCodec codec,
                  Counters.Counter readsCounter) throws IOException {
      this(conf, fs.open(file), 
           fs.getFileStatus(file).getLen(),
           codec, readsCounter);
    }

    /**
     * Construct an IFile Reader.
     * 
     * @param conf Configuration File 
     * @param in   The input stream
     * @param length Length of the data in the stream, including the checksum
     *               bytes.
     * @param codec codec
     * @param readsCounter Counter for records read from disk
     * @throws IOException
     */
    public FixedLengthReader(Configuration conf, FSDataInputStream in, long length, 
                  CompressionCodec codec,
                  Counters.Counter readsCounter) throws IOException {
      readRecordsCounter = readsCounter;
      checksumIn = new IFileInputStream(in,length);
      if (codec != null) {
        decompressor = CodecPool.getDecompressor(codec);
        this.in = codec.createInputStream(checksumIn, decompressor);
      } else {
        this.in = checksumIn;
      }
      this.fileLength = length;
      
      if (conf != null) {
        bufferSize = conf.getInt("io.file.buffer.size", DEFAULT_BUFFER_SIZE);
      }
    }
    
    public long getLength() { 
      return fileLength - checksumIn.getSize();
    }
    
    public long getPosition() throws IOException {    
      return checksumIn.getPosition(); 
    }
    
    /**
     * Read upto len bytes into buf starting at offset off.
     * 
     * @param buf buffer 
     * @param off offset
     * @param len length of buffer
     * @return the no. of bytes read
     * @throws IOException
     */
    private int readData(byte[] buf, int off, int len) throws IOException {
      int bytesRead = 0;
      while (bytesRead < len) {
        int n = in.read(buf, off+bytesRead, len-bytesRead);
        if (n < 0) {
          return bytesRead;
        }
        bytesRead += n;
      }
      return len;
    }
    
    void readNextBlock(int minSize) throws IOException {
      if (buffer == null) {
        buffer = new byte[bufferSize];
        dataIn.reset(buffer, 0, 0);
      }
      buffer = 
        rejigData(buffer, 
                  (bufferSize < minSize) ? new byte[minSize << 1] : buffer);
      bufferSize = buffer.length;
    }
    
    private byte[] rejigData(byte[] source, byte[] destination) 
    throws IOException{
      // Copy remaining data into the destination array
      int bytesRemaining = dataIn.getLength()-dataIn.getPosition();
      if (bytesRemaining > 0) {
        System.arraycopy(source, dataIn.getPosition(), 
            destination, 0, bytesRemaining);
      }
      
      // Read as much data as will fit from the underlying stream 
      int n = readData(destination, bytesRemaining, 
                       (destination.length - bytesRemaining));
      dataIn.reset(destination, 0, (bytesRemaining + n));
      
      return destination;
    }
    
    public boolean next(DataInputBuffer key, DataInputBuffer value) 
    throws IOException {
      // Sanity check
      if (eof) {
        throw new EOFException("Completed reading " + bytesRead);
      }
      
      // Check if we have enough data to read lengths
      if ((dataIn.getLength() - dataIn.getPosition()) < 2*MAX_VINT_SIZE) {
        readNextBlock(2*MAX_VINT_SIZE);
      }
      
      // Read key and value lengths
      int oldPos = dataIn.getPosition();
      if(first){
    	  keyLength = WritableUtils.readVInt(dataIn);
          valueLength = WritableUtils.readVInt(dataIn);

      }
      int pos = dataIn.getPosition();
      bytesRead += pos - oldPos;
      
      first = false;

      // Check for EOF
      if (keyLength == EOF_MARKER && valueLength == EOF_MARKER) {
        eof = true;
        return false;
      }
      
      // Sanity check
      if (keyLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
                              keyLength);
      }
      if (valueLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative value-length: " + 
                              valueLength);
      }
      
      final int recordLength = keyLength + valueLength;
      
      // Check if we have the raw key/value in the buffer
      if ((dataIn.getLength()-pos) < recordLength) {
        readNextBlock(recordLength);
        
        // Sanity check
        if ((dataIn.getLength() - dataIn.getPosition()) < recordLength) {
          throw new EOFException("Rec# " + recNo + ": Could read the next " +
          		                   " record");
        }
      }

      // Setup the key and value
      pos = dataIn.getPosition();
      byte[] data = dataIn.getData();
      key.reset(data, pos, keyLength);
      value.reset(data, (pos + keyLength), valueLength);
      
      // Position for the next record
      long skipped = dataIn.skip(recordLength);
      if (skipped != recordLength) {
        throw new IOException("Rec# " + recNo + ": Failed to skip past record " +
        		                  "of length: " + recordLength);
      }
      
      // Record the bytes read
      bytesRead += recordLength;

      ++recNo;
      ++numRecordsRead;

      return true;
    }

    public void close() throws IOException {
      // Return the decompressor
      if (decompressor != null) {
        decompressor.reset();
        CodecPool.returnDecompressor(decompressor);
        decompressor = null;
      }
      
      // Close the underlying stream
      in.close();
      
      // Release the buffer
      dataIn = null;
      buffer = null;
      if(readRecordsCounter != null) {
        readRecordsCounter.increment(numRecordsRead);
      }
    }
  }    
  
  /**
   * <code>IFile.InMemoryReader</code> to read map-outputs present in-memory.
   */
  public static class InMemoryReader<K, V> extends Reader<K, V> {
    RamManager ramManager;
    TaskAttemptID taskAttemptId;
    
    public InMemoryReader(RamManager ramManager, TaskAttemptID taskAttemptId,
                          byte[] data, int start, int length)
                          throws IOException {
      super(null, null, length - start, null, null);
      this.ramManager = ramManager;
      this.taskAttemptId = taskAttemptId;
      
      buffer = data;
      bufferSize = (int)fileLength;
      dataIn.reset(buffer, start, length);
    }
    
    @Override
    public long getPosition() throws IOException {
      // InMemoryReader does not initialize streams like Reader, so in.getPos()
      // would not work. Instead, return the number of uncompressed bytes read,
      // which will be correct since in-memory data is not compressed.
      return bytesRead;
    }
    
    @Override
    public long getLength() { 
      return fileLength;
    }
    
    private void dumpOnError() {
      File dumpFile = new File("../output/" + taskAttemptId + ".dump");
      System.err.println("Dumping corrupt map-output of " + taskAttemptId + 
                         " to " + dumpFile.getAbsolutePath());
      try {
        FileOutputStream fos = new FileOutputStream(dumpFile);
        fos.write(buffer, 0, bufferSize);
        fos.close();
      } catch (IOException ioe) {
        System.err.println("Failed to dump map-output of " + taskAttemptId);
      }
    }
    
    public boolean next(DataInputBuffer key, DataInputBuffer value) 
    throws IOException {
      try {
      // Sanity check
      if (eof) {
        throw new EOFException("Completed reading " + bytesRead);
      }
      
      // Read key and value lengths
      int oldPos = dataIn.getPosition();
      int keyLength = WritableUtils.readVInt(dataIn);
      int valueLength = WritableUtils.readVInt(dataIn);
      int pos = dataIn.getPosition();
      bytesRead += pos - oldPos;
      
      // Check for EOF
      if (keyLength == EOF_MARKER && valueLength == EOF_MARKER) {
        eof = true;
        return false;
      }
      
      // Sanity check
      if (keyLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative key-length: " + 
                              keyLength);
      }
      if (valueLength < 0) {
        throw new IOException("Rec# " + recNo + ": Negative value-length: " + 
                              valueLength);
      }

      final int recordLength = keyLength + valueLength;
      
      // Setup the key and value
      pos = dataIn.getPosition();
      byte[] data = dataIn.getData();
      key.reset(data, pos, keyLength);
      value.reset(data, (pos + keyLength), valueLength);
      
      // Position for the next record
      long skipped = dataIn.skip(recordLength);
      if (skipped != recordLength) {
        throw new IOException("Rec# " + recNo + ": Failed to skip past record of length: " + 
                              recordLength);
      }
      
      // Record the byte
      bytesRead += recordLength;

      ++recNo;
      
      return true;
      } catch (IOException ioe) {
        dumpOnError();
        throw ioe;
      }
    }
      
    public void close() {
      // Release
      dataIn = null;
      buffer = null;
      
      // Inform the RamManager
      ramManager.unreserve(bufferSize);
    }
  }
  
  public static class InMemoryTrippleReader<T1, T2, T3> extends TrippleReader<T1, T2, T3> {
    RamManager ramManager;
    TaskAttemptID taskAttemptId;
    
    public InMemoryTrippleReader(RamManager ramManager, TaskAttemptID taskAttemptId,
                          byte[] data, int start, int length)
                          throws IOException {
      super(null, null, length - start, null, null);
      this.ramManager = ramManager;
      this.taskAttemptId = taskAttemptId;
      
      buffer = data;
      bufferSize = (int)fileLength;
      dataIn.reset(buffer, start, length);
    }
    
    @Override
    public long getPosition() throws IOException {
      // InMemoryReader does not initialize streams like Reader, so in.getPos()
      // would not work. Instead, return the number of uncompressed bytes read,
      // which will be correct since in-memory data is not compressed.
      return bytesRead;
    }
    
    @Override
    public long getLength() { 
      return fileLength;
    }
    
    private void dumpOnError() {
      File dumpFile = new File("../output/" + taskAttemptId + ".dump");
      System.err.println("Dumping corrupt map-output of " + taskAttemptId + 
                         " to " + dumpFile.getAbsolutePath());
      try {
        FileOutputStream fos = new FileOutputStream(dumpFile);
        fos.write(buffer, 0, bufferSize);
        fos.close();
      } catch (IOException ioe) {
        System.err.println("Failed to dump map-output of " + taskAttemptId);
      }
    }
    
    public boolean next(DataInputBuffer t1, DataInputBuffer t2, DataInputBuffer t3) 
    throws IOException {
      try {
      // Sanity check
      if (eof) {
        throw new EOFException("Completed reading " + bytesRead);
      }
      
      // Read key and value lengths
      int oldPos = dataIn.getPosition();
      int t1Length = WritableUtils.readVInt(dataIn);
      int t2Length = WritableUtils.readVInt(dataIn);
      int t3Length = WritableUtils.readVInt(dataIn);
      int pos = dataIn.getPosition();
      bytesRead += pos - oldPos;
      
      // Check for EOF
      if (t1Length == EOF_MARKER && t2Length == EOF_MARKER && t3Length == EOF_MARKER) {
        eof = true;
        return false;
      }
      
      // Sanity check
      if (t1Length < 0) {
        throw new IOException("Rec# " + recNo + ": Negative t1-length: " + 
        		t1Length);
      }
      if (t2Length < 0) {
        throw new IOException("Rec# " + recNo + ": Negative t2-length: " + 
        		t2Length);
      }
      if (t3Length < 0) {
	      throw new IOException("Rec# " + recNo + ": Negative t3-length: " + 
	    		  t3Length);
	    }

      final int recordLength = t1Length + t2Length + t3Length;
      
      // Setup the key and value
      pos = dataIn.getPosition();
      byte[] data = dataIn.getData();
      t1.reset(data, pos, t1Length);
      t2.reset(data, (pos + t1Length), t2Length);
      t3.reset(data, (pos + t1Length + t2Length), t3Length);
      
      // Position for the next record
      long skipped = dataIn.skip(recordLength);
      if (skipped != recordLength) {
        throw new IOException("Rec# " + recNo + ": Failed to skip past record of length: " + 
                              recordLength);
      }
      
      // Record the byte
      bytesRead += recordLength;

      ++recNo;
      
      return true;
      } catch (IOException ioe) {
        dumpOnError();
        throw ioe;
      }
    }
      
    public void close() {
      // Release
      dataIn = null;
      buffer = null;
      
      // Inform the RamManager
      ramManager.unreserve(bufferSize);
    }
  }
}
