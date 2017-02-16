/*
 * Copyright 2017 Spotify AB.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.beam.sdk.io.hdfs;

import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.net.URI;
import java.security.PrivilegedExceptionAction;
import java.util.Random;
import java.util.Set;

import org.apache.avro.Schema;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.mapred.AvroKey;
import org.apache.avro.mapreduce.AvroKeyOutputFormat;
import org.apache.beam.sdk.coders.AvroCoder;
import org.apache.beam.sdk.coders.Coder;
import org.apache.beam.sdk.coders.StringUtf8Coder;
import org.apache.beam.sdk.io.Sink;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.io.Text;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.JobContext;
import org.apache.hadoop.mapreduce.JobID;
import org.apache.hadoop.mapreduce.RecordWriter;
import org.apache.hadoop.mapreduce.TaskAttemptContext;
import org.apache.hadoop.mapreduce.TaskAttemptID;
import org.apache.hadoop.mapreduce.TaskID;
import org.apache.hadoop.mapreduce.TaskType;
import org.apache.hadoop.mapreduce.lib.output.FileOutputCommitter;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.mapreduce.lib.output.TextOutputFormat;
import org.apache.hadoop.mapreduce.task.JobContextImpl;
import org.apache.hadoop.mapreduce.task.TaskAttemptContextImpl;

import javax.annotation.Nullable;

/**
 * A {@code Sink} for writing records to a Hadoop filesystem using a Hadoop file-based output
 * format.
 * @param <T> the type of elements of the input {@link org.apache.beam.sdk.values.PCollection}.
 * @param <K> the type of keys to be written to the sink via {@link FileOutputFormat}.
 * @param <V> the type of values to be written to the sink via {@link FileOutputFormat}.
 */
public class HDFSFileSink<T, K, V> extends Sink<T> {

  private static final JobID jobId = new JobID(
      Long.toString(System.currentTimeMillis()),
      new Random().nextInt(Integer.MAX_VALUE));

  private final String path;
  private final Class<? extends FileOutputFormat<K, V>> formatClass;
  private final Class<K> keyClass;
  private final Class<V> valueClass;
  private final SerializableFunction<T, KV<K, V>> outputConverter;
  private final SerializableConfiguration serializableConfiguration;
  private final String username;
  private final boolean validate;

  private HDFSFileSink(String path,
                       Class<? extends FileOutputFormat<K, V>> formatClass,
                       Class<K> keyClass,
                       Class<V> valueClass,
                       SerializableFunction<T, KV<K, V>> outputConverter,
                       SerializableConfiguration serializableConfiguration,
                       @Nullable String username,
                       boolean validate) {
    this.path = path;
    this.formatClass = formatClass;
    this.keyClass = keyClass;
    this.valueClass = valueClass;
    this.outputConverter = outputConverter;
    this.serializableConfiguration = serializableConfiguration;
    this.username = username;
    this.validate = validate;
  }

  // =======================================================================
  // Factory methods
  // =======================================================================

  public static <T, K, V, W extends FileOutputFormat<K, V>> HDFSFileSink<T, K, V>
  to(String path,
     Class<W> formatClass,
     Class<K> keyClass,
     Class<V> valueClass,
     SerializableFunction<T, KV<K, V>> outputConverter) {
    return new HDFSFileSink<>(
        path,
        formatClass,
        keyClass,
        valueClass,
        outputConverter,
        null,
        null,
        true);
  }

  public static <T> HDFSFileSink<T, NullWritable, Text> toText(String path) {
    SerializableFunction<T, KV<NullWritable, Text>> outputConverter =
        new SerializableFunction<T, KV<NullWritable, Text>>() {
          @Override
          public KV<NullWritable, Text> apply(T input) {
            return KV.of(NullWritable.get(), new Text(input.toString()));
          }
        };
    return to(path, TextOutputFormat.class, NullWritable.class, Text.class, outputConverter);
  }

  /**
   * Helper to create Avro sink given {@link AvroCoder}. Keep in mind that configuration
   * object is altered to enable Avro output.
   */
  public static <T> HDFSFileSink<T, AvroKey<T>, NullWritable> toAvro(String path,
                                                                     final AvroCoder<T> coder,
                                                                     Configuration conf) {
    SerializableFunction<T, KV<AvroKey<T>, NullWritable>> outputConverter =
        new SerializableFunction<T, KV<AvroKey<T>, NullWritable>>() {
          @Override
          public KV<AvroKey<T>, NullWritable> apply(T input) {
            return KV.of(new AvroKey<>(input), NullWritable.get());
          }
        };
    conf.set("avro.schema.output.key", coder.getSchema().toString());
    return to(
        path,
        AvroKeyOutputFormat.class,
        (Class<AvroKey<T>>) (Class<?>) AvroKey.class,
        NullWritable.class,
        outputConverter).withConfiguration(conf);
  }

  /**
   * Helper to create Avro sink given {@link Schema}. Keep in mind that configuration
   * object is altered to enable Avro output.
   */
  public static HDFSFileSink<GenericRecord, AvroKey<GenericRecord>, NullWritable>
  toAvro(String path, Schema schema, Configuration conf) {
    return toAvro(path, AvroCoder.of(schema), conf);
  }

  /**
   * Helper to create Avro sink given {@link Class}. Keep in mind that configuration
   * object is altered to enable Avro output.
   */
  public static <T> HDFSFileSink<T, AvroKey<T>, NullWritable> toAvro(String path,
                                                                     Class<T> cls,
                                                                     Configuration conf) {
    return toAvro(path, AvroCoder.of(cls), conf);
  }

  // =======================================================================
  // Builder methods
  // =======================================================================

  public HDFSFileSink<T, K, V> withConfiguration(Configuration conf) {
    SerializableConfiguration serializableConfiguration = new SerializableConfiguration(conf);
    return new HDFSFileSink<>(
        path,
        formatClass,
        keyClass,
        valueClass,
        outputConverter,
        serializableConfiguration,
        username,
        validate);
  }

  public HDFSFileSink<T, K, V> withUsername(@Nullable String username) {
    return new HDFSFileSink<>(
        path,
        formatClass,
        keyClass,
        valueClass,
        outputConverter,
        serializableConfiguration,
        username,
        validate);
  }

  public HDFSFileSink<T, K, V> withoutValidation() {
    return new HDFSFileSink<>(
        path,
        formatClass,
        keyClass,
        valueClass,
        outputConverter,
        serializableConfiguration,
        username,
        false);
  }

  // =======================================================================
  // Sink
  // =======================================================================

  @Override
  public void validate(PipelineOptions options) {
    if (validate) {
      try {
        UGIHelper.getBestUGI(username).doAs(new PrivilegedExceptionAction<Void>() {
          @Override
          public Void run() throws Exception {
            FileSystem fs = FileSystem.get(new URI(path),
                SerializableConfiguration.newConfiguration(serializableConfiguration));
            checkState(!fs.exists(new Path(path)), "Output path %s already exists", path);
            return null;
          }
        });
      } catch (IOException | InterruptedException e) {
        throw new RuntimeException(e);
      }
    }
  }

  @Override
  public Sink.WriteOperation<T, String> createWriteOperation(PipelineOptions options) {
    return new HDFSWriteOperation<>(this, path, formatClass);
  }

  private Job newJob() throws IOException {
    Job job = SerializableConfiguration.newJob(serializableConfiguration);
    job.setJobID(jobId);
    job.setOutputKeyClass(keyClass);
    job.setOutputValueClass(valueClass);
    return job;
  }

  // =======================================================================
  // WriteOperation
  // =======================================================================

  /** {{@link WriteOperation}} for HDFS. */
  private static class HDFSWriteOperation<T, K, V> extends WriteOperation<T, String> {

    private final HDFSFileSink<T, K, V> sink;
    private final String path;
    private final Class<? extends FileOutputFormat<K, V>> formatClass;

    HDFSWriteOperation(HDFSFileSink<T, K, V> sink,
                       String path,
                       Class<? extends FileOutputFormat<K, V>> formatClass) {
      this.sink = sink;
      this.path = path;
      this.formatClass = formatClass;
    }

    @Override
    public void initialize(PipelineOptions options) throws Exception {
      Job job = sink.newJob();
      FileOutputFormat.setOutputPath(job, new Path(path));
    }

    @Override
    public void finalize(final Iterable<String> writerResults, PipelineOptions options)
        throws Exception {
      UGIHelper.getBestUGI(sink.username).doAs(new PrivilegedExceptionAction<Void>() {
        @Override
        public Void run() throws Exception {
          doFinalize(writerResults);
          return null;
        }
      });
    }

    private void doFinalize(Iterable<String> writerResults) throws Exception {
      Job job = sink.newJob();
      FileSystem fs = FileSystem.get(new URI(path), job.getConfiguration());

      // If there are 0 output shards, just create output folder.
      if (!writerResults.iterator().hasNext()) {
        fs.mkdirs(new Path(path));
        return;
      }

      // job successful
      JobContext context = new JobContextImpl(job.getConfiguration(), job.getJobID());
      FileOutputCommitter outputCommitter = new FileOutputCommitter(new Path(path), context);
      outputCommitter.commitJob(context);

      // get actual output shards
      Set<String> actual = Sets.newHashSet();
      FileStatus[] statuses = fs.listStatus(new Path(path), new PathFilter() {
        @Override
        public boolean accept(Path path) {
          String name = path.getName();
          return !name.startsWith("_") && !name.startsWith(".");
        }
      });

      // get expected output shards
      Set<String> expected = Sets.newHashSet(writerResults);
      checkState(
          expected.size() == Lists.newArrayList(writerResults).size(),
          "Data loss due to writer results hash collision");
      for (FileStatus s : statuses) {
        String name = s.getPath().getName();
        int pos = name.indexOf('.');
        actual.add(pos > 0 ? name.substring(0, pos) : name);
      }

      checkState(actual.equals(expected), "Writer results and output files do not match");

      // rename output shards to Hadoop style, i.e. part-r-00000.txt
      int i = 0;
      for (FileStatus s : statuses) {
        String name = s.getPath().getName();
        int pos = name.indexOf('.');
        String ext = pos > 0 ? name.substring(pos) : "";
        fs.rename(
            s.getPath(),
            new Path(s.getPath().getParent(), String.format("part-r-%05d%s", i, ext)));
        i++;
      }
    }

    @Override
    public Writer<T, String> createWriter(PipelineOptions options) throws Exception {
      return new HDFSWriter<>(this, path, formatClass);
    }

    @Override
    public Sink<T> getSink() {
      return sink;
    }

    @Override
    public Coder<String> getWriterResultCoder() {
      return StringUtf8Coder.of();
    }

  }

  // =======================================================================
  // Writer
  // =======================================================================

  private static class HDFSWriter<T, K, V> extends Writer<T, String> {

    private final HDFSWriteOperation<T, K, V> writeOperation;
    private final String path;
    private final Class<? extends FileOutputFormat<K, V>> formatClass;

    // unique hash for each task
    private int hash;

    private TaskAttemptContext context;
    private RecordWriter<K, V> recordWriter;
    private FileOutputCommitter outputCommitter;

    HDFSWriter(HDFSWriteOperation<T, K, V> writeOperation,
               String path,
               Class<? extends FileOutputFormat<K, V>> formatClass) {
      this.writeOperation = writeOperation;
      this.path = path;
      this.formatClass = formatClass;
    }

    @Override
    public void open(final String uId) throws Exception {
      UGIHelper.getBestUGI(writeOperation.sink.username).doAs(
          new PrivilegedExceptionAction<Void>() {
            @Override
            public Void run() throws Exception {
              doOpen(uId);
              return null;
            }
          }
      );
    }

    private void doOpen(String uId) throws Exception {
      this.hash = uId.hashCode();

      Job job = writeOperation.sink.newJob();
      FileOutputFormat.setOutputPath(job, new Path(path));

      // Each Writer is responsible for writing one bundle of elements and is represented by one
      // unique Hadoop task based on uId/hash. All tasks share the same job ID. Since Dataflow
      // handles retrying of failed bundles, each task has one attempt only.
      JobID jobId = job.getJobID();
      TaskID taskId = new TaskID(jobId, TaskType.REDUCE, hash);
      context = new TaskAttemptContextImpl(job.getConfiguration(), new TaskAttemptID(taskId, 0));

      FileOutputFormat<K, V> outputFormat = formatClass.newInstance();
      recordWriter = outputFormat.getRecordWriter(context);
      outputCommitter = (FileOutputCommitter) outputFormat.getOutputCommitter(context);
    }

    @Override
    public void write(T value) throws Exception {
      checkNotNull(recordWriter,
          "Record writer can't be null. Make sure to open Writer first!");
      KV<K, V> kv = writeOperation.sink.outputConverter.apply(value);
      recordWriter.write(kv.getKey(), kv.getValue());
    }

    @Override
    public String close() throws Exception {
      return UGIHelper.getBestUGI(writeOperation.sink.username).doAs(
          new PrivilegedExceptionAction<String>() {
            @Override
            public String run() throws Exception {
              return doClose();
            }
          });
    }

    private String doClose() throws Exception {
      // task/attempt successful
      recordWriter.close(context);
      outputCommitter.commitTask(context);

      // result is prefix of the output file name
      return String.format("part-r-%d", hash);
    }

    @Override
    public WriteOperation<T, String> getWriteOperation() {
      return writeOperation;
    }

  }

}
