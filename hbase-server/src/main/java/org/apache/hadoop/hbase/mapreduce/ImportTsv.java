/**
 *
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
package org.apache.hadoop.hbase.mapreduce;

import static java.lang.String.format;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.io.ImmutableBytesWritable;
import org.apache.hadoop.hbase.util.Base64;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.lib.input.FileInputFormat;
import org.apache.hadoop.mapreduce.lib.input.TextInputFormat;
import org.apache.hadoop.mapreduce.lib.output.FileOutputFormat;
import org.apache.hadoop.util.GenericOptionsParser;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.util.ToolRunner;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;

/**
 * Tool to import data from a TSV file.
 *
 * This tool is rather simplistic - it doesn't do any quoting or
 * escaping, but is useful for many data loads.
 *
 * @see ImportTsv#usage(String)
 */
@InterfaceAudience.Public
@InterfaceStability.Stable
public class ImportTsv extends Configured implements Tool {

  protected static final Log LOG = LogFactory.getLog(ImportTsv.class);

  final static String NAME = "importtsv";

  public final static String MAPPER_CONF_KEY = "importtsv.mapper.class";
  public final static String BULK_OUTPUT_CONF_KEY = "importtsv.bulk.output";
  public final static String TIMESTAMP_CONF_KEY = "importtsv.timestamp";
  public final static String JOB_NAME_CONF_KEY = "mapred.job.name";
  // TODO: the rest of these configs are used exclusively by TsvImporterMapper.
  // Move them out of the tool and let the mapper handle its own validation.
  public final static String SKIP_LINES_CONF_KEY = "importtsv.skip.bad.lines";
  public final static String COLUMNS_CONF_KEY = "importtsv.columns";
  public final static String SEPARATOR_CONF_KEY = "importtsv.separator";

  final static String DEFAULT_SEPARATOR = "\t";
  final static Class DEFAULT_MAPPER = TsvImporterMapper.class;

  public static class TsvParser {
    /**
     * Column families and qualifiers mapped to the TSV columns
     */
    private final byte[][] families;
    private final byte[][] qualifiers;

    private final byte separatorByte;

    private int rowKeyColumnIndex;

    private int maxColumnCount;

    // Default value must be negative
    public static final int DEFAULT_TIMESTAMP_COLUMN_INDEX = -1;

    private int timestampKeyColumnIndex = DEFAULT_TIMESTAMP_COLUMN_INDEX;

    public static final String ROWKEY_COLUMN_SPEC = "HBASE_ROW_KEY";

    public static final String TIMESTAMPKEY_COLUMN_SPEC = "HBASE_TS_KEY";

    /**
     * @param columnsSpecification the list of columns to parser out, comma separated.
     * The row key should be the special token TsvParser.ROWKEY_COLUMN_SPEC
     */
    public TsvParser(String columnsSpecification, String separatorStr) {
      // Configure separator
      byte[] separator = Bytes.toBytes(separatorStr);
      Preconditions.checkArgument(separator.length == 1,
        "TsvParser only supports single-byte separators");
      separatorByte = separator[0];

      // Configure columns
      ArrayList<String> columnStrings = Lists.newArrayList(
        Splitter.on(',').trimResults().split(columnsSpecification));

      maxColumnCount = columnStrings.size();
      families = new byte[maxColumnCount][];
      qualifiers = new byte[maxColumnCount][];

      for (int i = 0; i < columnStrings.size(); i++) {
        String str = columnStrings.get(i);
        if (ROWKEY_COLUMN_SPEC.equals(str)) {
          rowKeyColumnIndex = i;
          continue;
        }
        
        if (TIMESTAMPKEY_COLUMN_SPEC.equals(str)) {
          timestampKeyColumnIndex = i;
          continue;
        }
        
        String[] parts = str.split(":", 2);
        if (parts.length == 1) {
          families[i] = str.getBytes();
          qualifiers[i] = HConstants.EMPTY_BYTE_ARRAY;
        } else {
          families[i] = parts[0].getBytes();
          qualifiers[i] = parts[1].getBytes();
        }
      }
    }

    public boolean hasTimestamp() {
      return timestampKeyColumnIndex != DEFAULT_TIMESTAMP_COLUMN_INDEX;
    }

    public int getTimestampKeyColumnIndex() {
      return timestampKeyColumnIndex;
    }

    public int getRowKeyColumnIndex() {
      return rowKeyColumnIndex;
    }
    public byte[] getFamily(int idx) {
      return families[idx];
    }
    public byte[] getQualifier(int idx) {
      return qualifiers[idx];
    }

    public ParsedLine parse(byte[] lineBytes, int length)
    throws BadTsvLineException {
      // Enumerate separator offsets
      ArrayList<Integer> tabOffsets = new ArrayList<Integer>(maxColumnCount);
      for (int i = 0; i < length; i++) {
        if (lineBytes[i] == separatorByte) {
          tabOffsets.add(i);
        }
      }
      if (tabOffsets.isEmpty()) {
        throw new BadTsvLineException("No delimiter");
      }

      tabOffsets.add(length);

      if (tabOffsets.size() > maxColumnCount) {
        throw new BadTsvLineException("Excessive columns");
      } else if (tabOffsets.size() <= getRowKeyColumnIndex()) {
        throw new BadTsvLineException("No row key");
      } else if (hasTimestamp()
          && tabOffsets.size() <= getTimestampKeyColumnIndex()) {
        throw new BadTsvLineException("No timestamp");
      }
      return new ParsedLine(tabOffsets, lineBytes);
    }

    class ParsedLine {
      private final ArrayList<Integer> tabOffsets;
      private byte[] lineBytes;

      ParsedLine(ArrayList<Integer> tabOffsets, byte[] lineBytes) {
        this.tabOffsets = tabOffsets;
        this.lineBytes = lineBytes;
      }

      public int getRowKeyOffset() {
        return getColumnOffset(rowKeyColumnIndex);
      }
      public int getRowKeyLength() {
        return getColumnLength(rowKeyColumnIndex);
      }
      
      public long getTimestamp(long ts) throws BadTsvLineException {
        // Return ts if HBASE_TS_KEY is not configured in column spec
        if (!hasTimestamp()) {
          return ts;
        }

        String timeStampStr = Bytes.toString(lineBytes,
            getColumnOffset(timestampKeyColumnIndex),
            getColumnLength(timestampKeyColumnIndex));
        try {
          return Long.parseLong(timeStampStr);
        } catch (NumberFormatException nfe) {
          // treat this record as bad record
          throw new BadTsvLineException("Invalid timestamp " + timeStampStr);
        }
      }
      
      public int getColumnOffset(int idx) {
        if (idx > 0)
          return tabOffsets.get(idx - 1) + 1;
        else
          return 0;
      }
      public int getColumnLength(int idx) {
        return tabOffsets.get(idx) - getColumnOffset(idx);
      }
      public int getColumnCount() {
        return tabOffsets.size();
      }
      public byte[] getLineBytes() {
        return lineBytes;
      }
    }

    public static class BadTsvLineException extends Exception {
      public BadTsvLineException(String err) {
        super(err);
      }
      private static final long serialVersionUID = 1L;
    }
  }

  /**
   * Sets up the actual job.
   *
   * @param conf  The current configuration.
   * @param args  The command line parameters.
   * @return The newly created job.
   * @throws IOException When setting up the job fails.
   */
  public static Job createSubmittableJob(Configuration conf, String[] args)
      throws IOException, ClassNotFoundException {

    HBaseAdmin admin = new HBaseAdmin(conf);

    // Support non-XML supported characters
    // by re-encoding the passed separator as a Base64 string.
    String actualSeparator = conf.get(SEPARATOR_CONF_KEY);
    if (actualSeparator != null) {
      conf.set(SEPARATOR_CONF_KEY,
               Base64.encodeBytes(actualSeparator.getBytes()));
    }

    // See if a non-default Mapper was set
    String mapperClassName = conf.get(MAPPER_CONF_KEY);
    Class mapperClass = mapperClassName != null ?
        Class.forName(mapperClassName) : DEFAULT_MAPPER;

    conf.setStrings("io.serializations", conf.get("io.serializations"),
        MutationSerialization.class.getName(), ResultSerialization.class.getName());

    String tableName = args[0];
    Path inputDir = new Path(args[1]);
    String jobName = conf.get(JOB_NAME_CONF_KEY,NAME + "_" + tableName);
    Job job = new Job(conf, jobName);
    job.setJarByClass(mapperClass);
    FileInputFormat.setInputPaths(job, inputDir);
    job.setInputFormatClass(TextInputFormat.class);
    job.setMapperClass(mapperClass);

    String hfileOutPath = conf.get(BULK_OUTPUT_CONF_KEY);
    String columns[] = conf.getStrings(COLUMNS_CONF_KEY);
    if (hfileOutPath != null) {
      if (!admin.tableExists(tableName)) {
        LOG.warn(format("Table '%s' does not exist.", tableName));
        // TODO: this is backwards. Instead of depending on the existence of a table,
        // create a sane splits file for HFileOutputFormat based on data sampling.
        createTable(admin, tableName, columns);
      }
      HTable table = new HTable(conf, tableName);
      job.setReducerClass(PutSortReducer.class);
      Path outputDir = new Path(hfileOutPath);
      FileOutputFormat.setOutputPath(job, outputDir);
      job.setMapOutputKeyClass(ImmutableBytesWritable.class);
      job.setMapOutputValueClass(Put.class);
      job.setCombinerClass(PutCombiner.class);
      HFileOutputFormat.configureIncrementalLoad(job, table);
    } else {
      // No reducers. Just write straight to table. Call initTableReducerJob
      // to set up the TableOutputFormat.
      TableMapReduceUtil.initTableReducerJob(tableName, null, job);
      job.setNumReduceTasks(0);
    }

    TableMapReduceUtil.addDependencyJars(job);
    TableMapReduceUtil.addDependencyJars(job.getConfiguration(),
        com.google.common.base.Function.class /* Guava used by TsvParser */);
    return job;
  }

  private static void createTable(HBaseAdmin admin, String tableName, String[] columns)
      throws IOException {
    HTableDescriptor htd = new HTableDescriptor(tableName.getBytes());
    Set<String> cfSet = new HashSet<String>();
    for (String aColumn : columns) {
      if (TsvParser.ROWKEY_COLUMN_SPEC.equals(aColumn)) continue;
      // we are only concerned with the first one (in case this is a cf:cq)
      cfSet.add(aColumn.split(":", 2)[0]);
    }
    for (String cf : cfSet) {
      HColumnDescriptor hcd = new HColumnDescriptor(Bytes.toBytes(cf));
      htd.addFamily(hcd);
    }
    LOG.warn(format("Creating table '%s' with '%s' columns and default descriptors.",
      tableName, cfSet));
    admin.createTable(htd);
  }

  /*
   * @param errorMsg Error message.  Can be null.
   */
  private static void usage(final String errorMsg) {
    if (errorMsg != null && errorMsg.length() > 0) {
      System.err.println("ERROR: " + errorMsg);
    }
    String usage = 
      "Usage: " + NAME + " -D"+ COLUMNS_CONF_KEY + "=a,b,c <tablename> <inputdir>\n" +
      "\n" +
      "Imports the given input directory of TSV data into the specified table.\n" +
      "\n" +
      "The column names of the TSV data must be specified using the -D" + COLUMNS_CONF_KEY + "\n" +
      "option. This option takes the form of comma-separated column names, where each\n" +
      "column name is either a simple column family, or a columnfamily:qualifier. The special\n" +
      "column name " + TsvParser.ROWKEY_COLUMN_SPEC + " is used to designate that this column should be used\n" +
      "as the row key for each imported record. You must specify exactly one column\n" +
      "to be the row key, and you must specify a column name for every column that exists in the\n" +
      "input data. Another special column" + TsvParser.TIMESTAMPKEY_COLUMN_SPEC +
      " designates that this column should be\n" +
      "used as timestamp for each record. Unlike " + TsvParser.ROWKEY_COLUMN_SPEC + ", " +
      TsvParser.TIMESTAMPKEY_COLUMN_SPEC + " is optional.\n" +
      "You must specify at most one column as timestamp key for each imported record.\n" +
      "Record with invalid timestamps (blank, non-numeric) will be treated as bad record.\n" +
      "Note: if you use this option, then '" + TIMESTAMP_CONF_KEY + "' option will be ignored.\n" +
      "\n" +
      "By default importtsv will load data directly into HBase. To instead generate\n" +
      "HFiles of data to prepare for a bulk data load, pass the option:\n" +
      "  -D" + BULK_OUTPUT_CONF_KEY + "=/path/for/output\n" +
      "  Note: if you do not use this option, then the target table must already exist in HBase\n" +
      "\n" +
      "Other options that may be specified with -D include:\n" +
      "  -D" + SKIP_LINES_CONF_KEY + "=false - fail if encountering an invalid line\n" +
      "  '-D" + SEPARATOR_CONF_KEY + "=|' - eg separate on pipes instead of tabs\n" +
      "  -D" + TIMESTAMP_CONF_KEY + "=currentTimeAsLong - use the specified timestamp for the import\n" +
      "  -D" + MAPPER_CONF_KEY + "=my.Mapper - A user-defined Mapper to use instead of " +
      DEFAULT_MAPPER.getName() + "\n" +
      "  -D" + JOB_NAME_CONF_KEY + "=jobName - use the specified mapreduce job name for the import\n" +
      "For performance consider the following options:\n" +
      "  -Dmapred.map.tasks.speculative.execution=false\n" +
      "  -Dmapred.reduce.tasks.speculative.execution=false";

    System.err.println(usage);
  }

  @Override
  public int run(String[] args) throws Exception {
    setConf(HBaseConfiguration.create(getConf()));
    String[] otherArgs = new GenericOptionsParser(getConf(), args).getRemainingArgs();
    if (otherArgs.length < 2) {
      usage("Wrong number of arguments: " + otherArgs.length);
      return -1;
    }

    // When MAPPER_CONF_KEY is null, the user wants to use the provided TsvImporterMapper, so
    // perform validation on these additional args. When it's not null, user has provided their
    // own mapper, thus these validation are not relevant.
    // TODO: validation for TsvImporterMapper, not this tool. Move elsewhere.
    if (null == getConf().get(MAPPER_CONF_KEY)) {
      // Make sure columns are specified
      String columns[] = getConf().getStrings(COLUMNS_CONF_KEY);
      if (columns == null) {
        usage("No columns specified. Please specify with -D" +
            COLUMNS_CONF_KEY+"=...");
        return -1;
      }

      // Make sure they specify exactly one column as the row key
      int rowkeysFound = 0;
      for (String col : columns) {
        if (col.equals(TsvParser.ROWKEY_COLUMN_SPEC)) rowkeysFound++;
      }
      if (rowkeysFound != 1) {
        usage("Must specify exactly one column as " + TsvParser.ROWKEY_COLUMN_SPEC);
        return -1;
      }

      // Make sure we have at most one column as the timestamp key
      int tskeysFound = 0;
      for (String col : columns) {
        if (col.equals(TsvParser.TIMESTAMPKEY_COLUMN_SPEC))
          tskeysFound++;
      }
      if (tskeysFound > 1) {
        usage("Must specify at most one column as "
            + TsvParser.TIMESTAMPKEY_COLUMN_SPEC);
        return -1;
      }
    
      // Make sure one or more columns are specified excluding rowkey and
      // timestamp key
      if (columns.length - (rowkeysFound + tskeysFound) < 1) {
        usage("One or more columns in addition to the row key and timestamp(optional) are required");
        return -1;
      }
    }

    // If timestamp option is not specified, use current system time.
    long timstamp = getConf().getLong(TIMESTAMP_CONF_KEY, System.currentTimeMillis());

    // Set it back to replace invalid timestamp (non-numeric) with current
    // system time
    getConf().setLong(TIMESTAMP_CONF_KEY, timstamp);
    
    Job job = createSubmittableJob(getConf(), otherArgs);
    return job.waitForCompletion(true) ? 0 : 1;
  }

  public static void main(String[] args) throws Exception {
    int status = ToolRunner.run(new ImportTsv(), args);
    System.exit(status);
  }
}
