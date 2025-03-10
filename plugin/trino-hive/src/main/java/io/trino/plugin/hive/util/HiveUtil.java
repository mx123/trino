/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.hive.util;

import com.google.common.base.CharMatcher;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.base.VerifyException;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import io.airlift.compress.lzo.LzoCodec;
import io.airlift.compress.lzo.LzopCodec;
import io.airlift.slice.Slice;
import io.airlift.slice.SliceUtf8;
import io.airlift.slice.Slices;
import io.trino.hadoop.TextLineLengthLimitExceededException;
import io.trino.orc.OrcWriterOptions;
import io.trino.plugin.hive.HiveColumnHandle;
import io.trino.plugin.hive.HivePartitionKey;
import io.trino.plugin.hive.HiveStorageFormat;
import io.trino.plugin.hive.HiveTimestampPrecision;
import io.trino.plugin.hive.HiveType;
import io.trino.plugin.hive.avro.TrinoAvroSerDe;
import io.trino.plugin.hive.metastore.Column;
import io.trino.plugin.hive.metastore.SortingColumn;
import io.trino.plugin.hive.metastore.Table;
import io.trino.spi.ErrorCodeSupplier;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.predicate.NullableValue;
import io.trino.spi.type.ArrayType;
import io.trino.spi.type.CharType;
import io.trino.spi.type.DecimalType;
import io.trino.spi.type.Int128;
import io.trino.spi.type.MapType;
import io.trino.spi.type.RowType;
import io.trino.spi.type.Type;
import io.trino.spi.type.TypeManager;
import io.trino.spi.type.VarbinaryType;
import io.trino.spi.type.VarcharType;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.ql.io.SymlinkTextInputFormat;
import org.apache.hadoop.hive.ql.io.parquet.MapredParquetInputFormat;
import org.apache.hadoop.hive.serde2.AbstractSerDe;
import org.apache.hadoop.hive.serde2.Deserializer;
import org.apache.hadoop.hive.serde2.SerDeException;
import org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector;
import org.apache.hadoop.hive.serde2.objectinspector.StructObjectInspector;
import org.apache.hadoop.hive.serde2.typeinfo.StructTypeInfo;
import org.apache.hadoop.io.Writable;
import org.apache.hadoop.io.WritableComparable;
import org.apache.hadoop.io.compress.CompressionCodec;
import org.apache.hadoop.io.compress.CompressionCodecFactory;
import org.apache.hadoop.mapred.FileSplit;
import org.apache.hadoop.mapred.InputFormat;
import org.apache.hadoop.mapred.JobConf;
import org.apache.hadoop.mapred.RecordReader;
import org.apache.hadoop.mapred.Reporter;
import org.apache.hadoop.mapred.TextInputFormat;
import org.apache.hadoop.util.ReflectionUtils;
import org.joda.time.DateTimeZone;
import org.joda.time.Days;
import org.joda.time.LocalDateTime;
import org.joda.time.LocalTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;
import org.joda.time.format.DateTimeParser;
import org.joda.time.format.DateTimePrinter;

import javax.annotation.Nullable;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Properties;
import java.util.function.Function;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.google.common.collect.Iterables.concat;
import static com.google.common.collect.Lists.newArrayList;
import static io.airlift.slice.Slices.utf8Slice;
import static io.trino.hdfs.ConfigurationUtils.copy;
import static io.trino.hdfs.ConfigurationUtils.toJobConf;
import static io.trino.hive.thrift.metastore.hive_metastoreConstants.FILE_INPUT_FORMAT;
import static io.trino.plugin.hive.HiveColumnHandle.ColumnType.PARTITION_KEY;
import static io.trino.plugin.hive.HiveColumnHandle.ColumnType.REGULAR;
import static io.trino.plugin.hive.HiveColumnHandle.bucketColumnHandle;
import static io.trino.plugin.hive.HiveColumnHandle.createBaseColumn;
import static io.trino.plugin.hive.HiveColumnHandle.fileModifiedTimeColumnHandle;
import static io.trino.plugin.hive.HiveColumnHandle.fileSizeColumnHandle;
import static io.trino.plugin.hive.HiveColumnHandle.isBucketColumnHandle;
import static io.trino.plugin.hive.HiveColumnHandle.isFileModifiedTimeColumnHandle;
import static io.trino.plugin.hive.HiveColumnHandle.isFileSizeColumnHandle;
import static io.trino.plugin.hive.HiveColumnHandle.isPartitionColumnHandle;
import static io.trino.plugin.hive.HiveColumnHandle.isPathColumnHandle;
import static io.trino.plugin.hive.HiveColumnHandle.partitionColumnHandle;
import static io.trino.plugin.hive.HiveColumnHandle.pathColumnHandle;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_BAD_DATA;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_CANNOT_OPEN_SPLIT;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_INVALID_METADATA;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_INVALID_PARTITION_VALUE;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_SERDE_NOT_FOUND;
import static io.trino.plugin.hive.HiveErrorCode.HIVE_UNSUPPORTED_FORMAT;
import static io.trino.plugin.hive.HiveMetadata.ORC_BLOOM_FILTER_COLUMNS_KEY;
import static io.trino.plugin.hive.HiveMetadata.ORC_BLOOM_FILTER_FPP_KEY;
import static io.trino.plugin.hive.HiveMetadata.SKIP_FOOTER_COUNT_KEY;
import static io.trino.plugin.hive.HiveMetadata.SKIP_HEADER_COUNT_KEY;
import static io.trino.plugin.hive.HivePartitionKey.HIVE_DEFAULT_DYNAMIC_PARTITION;
import static io.trino.plugin.hive.HiveStorageFormat.TEXTFILE;
import static io.trino.plugin.hive.HiveTableProperties.ORC_BLOOM_FILTER_FPP;
import static io.trino.plugin.hive.HiveType.toHiveTypes;
import static io.trino.plugin.hive.metastore.SortingColumn.Order.ASCENDING;
import static io.trino.plugin.hive.metastore.SortingColumn.Order.DESCENDING;
import static io.trino.plugin.hive.util.HiveBucketing.isSupportedBucketing;
import static io.trino.plugin.hive.util.HiveClassNames.AVRO_SERDE_CLASS;
import static io.trino.plugin.hive.util.HiveClassNames.LAZY_SIMPLE_SERDE_CLASS;
import static io.trino.plugin.hive.util.SerdeConstants.COLLECTION_DELIM;
import static io.trino.plugin.hive.util.SerdeConstants.LIST_COLUMNS;
import static io.trino.plugin.hive.util.SerdeConstants.LIST_COLUMN_TYPES;
import static io.trino.plugin.hive.util.SerdeConstants.SERIALIZATION_LIB;
import static io.trino.spi.StandardErrorCode.GENERIC_INTERNAL_ERROR;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.BigintType.BIGINT;
import static io.trino.spi.type.BooleanType.BOOLEAN;
import static io.trino.spi.type.Chars.trimTrailingSpaces;
import static io.trino.spi.type.DateTimeEncoding.packDateTimeWithZone;
import static io.trino.spi.type.DateType.DATE;
import static io.trino.spi.type.DoubleType.DOUBLE;
import static io.trino.spi.type.IntegerType.INTEGER;
import static io.trino.spi.type.RealType.REAL;
import static io.trino.spi.type.SmallintType.SMALLINT;
import static io.trino.spi.type.TimestampType.TIMESTAMP_MILLIS;
import static io.trino.spi.type.TimestampWithTimeZoneType.TIMESTAMP_TZ_MILLIS;
import static io.trino.spi.type.Timestamps.MICROSECONDS_PER_MILLISECOND;
import static io.trino.spi.type.TinyintType.TINYINT;
import static java.lang.Byte.parseByte;
import static java.lang.Double.parseDouble;
import static java.lang.Float.floatToRawIntBits;
import static java.lang.Float.parseFloat;
import static java.lang.Integer.parseInt;
import static java.lang.Long.parseLong;
import static java.lang.Math.floorDiv;
import static java.lang.Short.parseShort;
import static java.lang.String.format;
import static java.math.RoundingMode.UNNECESSARY;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Locale.ENGLISH;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;
import static org.apache.hadoop.hive.serde2.ColumnProjectionUtils.READ_ALL_COLUMNS;
import static org.apache.hadoop.hive.serde2.ColumnProjectionUtils.READ_COLUMN_IDS_CONF_STR;
import static org.apache.hadoop.hive.serde2.objectinspector.ObjectInspector.Category;

public final class HiveUtil
{
    public static final String SPARK_TABLE_PROVIDER_KEY = "spark.sql.sources.provider";
    public static final String DELTA_LAKE_PROVIDER = "delta";
    public static final String SPARK_TABLE_BUCKET_NUMBER_KEY = "spark.sql.sources.schema.numBuckets";

    public static final String ICEBERG_TABLE_TYPE_NAME = "table_type";
    public static final String ICEBERG_TABLE_TYPE_VALUE = "iceberg";

    // Input formats class names are listed below as String due to hudi-hadoop-mr dependency is not in the context of trino-hive plugin
    private static final String HUDI_PARQUET_INPUT_FORMAT = "org.apache.hudi.hadoop.HoodieParquetInputFormat";
    private static final String HUDI_PARQUET_REALTIME_INPUT_FORMAT = "org.apache.hudi.hadoop.realtime.HoodieParquetRealtimeInputFormat";
    private static final String HUDI_INPUT_FORMAT = "com.uber.hoodie.hadoop.HoodieInputFormat";
    private static final String HUDI_REALTIME_INPUT_FORMAT = "com.uber.hoodie.hadoop.realtime.HoodieRealtimeInputFormat";

    private static final HexFormat HEX_UPPER_FORMAT = HexFormat.of().withUpperCase();

    private static final LocalDateTime EPOCH_DAY = new LocalDateTime(1970, 1, 1, 0, 0);
    private static final DateTimeFormatter HIVE_DATE_PARSER;
    private static final DateTimeFormatter HIVE_TIMESTAMP_PARSER;
    private static final Field COMPRESSION_CODECS_FIELD;

    private static final String BIG_DECIMAL_POSTFIX = "BD";

    private static final Splitter COLUMN_NAMES_SPLITTER = Splitter.on(',').trimResults().omitEmptyStrings();

    private static final CharMatcher PATH_CHAR_TO_ESCAPE = CharMatcher.inRange((char) 0, (char) 31)
            .or(CharMatcher.anyOf("\"#%'*/:=?\\\u007F{[]^"))
            .precomputed();

    static {
        DateTimeParser[] timestampWithoutTimeZoneParser = {
                DateTimeFormat.forPattern("yyyy-M-d").getParser(),
                DateTimeFormat.forPattern("yyyy-M-d H:m").getParser(),
                DateTimeFormat.forPattern("yyyy-M-d H:m:s").getParser(),
                DateTimeFormat.forPattern("yyyy-M-d H:m:s.SSS").getParser(),
                DateTimeFormat.forPattern("yyyy-M-d H:m:s.SSSSSSS").getParser(),
                DateTimeFormat.forPattern("yyyy-M-d H:m:s.SSSSSSSSS").getParser(),
        };
        DateTimePrinter timestampWithoutTimeZonePrinter = DateTimeFormat.forPattern("yyyy-MM-dd HH:mm:ss.SSSSSSSSS").getPrinter();
        HIVE_TIMESTAMP_PARSER = new DateTimeFormatterBuilder().append(timestampWithoutTimeZonePrinter, timestampWithoutTimeZoneParser).toFormatter().withZoneUTC();
        HIVE_DATE_PARSER = new DateTimeFormatterBuilder().append(timestampWithoutTimeZonePrinter, timestampWithoutTimeZoneParser).toFormatter().withZoneUTC();

        try {
            COMPRESSION_CODECS_FIELD = TextInputFormat.class.getDeclaredField("compressionCodecs");
            COMPRESSION_CODECS_FIELD.setAccessible(true);
        }
        catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private HiveUtil()
    {
    }

    public static RecordReader<?, ?> createRecordReader(Configuration configuration, Path path, long start, long length, Properties schema, List<HiveColumnHandle> columns)
    {
        // determine which hive columns we will read
        List<HiveColumnHandle> readColumns = columns.stream()
                .filter(column -> column.getColumnType() == REGULAR)
                .collect(toImmutableList());

        // Projected columns are not supported here
        readColumns.forEach(readColumn -> checkArgument(readColumn.isBaseColumn(), "column %s is not a base column", readColumn.getName()));

        List<Integer> readHiveColumnIndexes = readColumns.stream()
                .map(HiveColumnHandle::getBaseHiveColumnIndex)
                .collect(toImmutableList());

        // Tell hive the columns we would like to read, this lets hive optimize reading column oriented files
        configuration = copy(configuration);
        setReadColumns(configuration, readHiveColumnIndexes);

        InputFormat<?, ?> inputFormat = getInputFormat(configuration, schema, true);
        JobConf jobConf = toJobConf(configuration);
        FileSplit fileSplit = new FileSplit(path, start, length, (String[]) null);

        // propagate serialization configuration to getRecordReader
        schema.stringPropertyNames().stream()
                .filter(name -> name.startsWith("serialization."))
                .forEach(name -> jobConf.set(name, schema.getProperty(name)));

        configureCompressionCodecs(jobConf);

        try {
            @SuppressWarnings("unchecked")
            RecordReader<? extends WritableComparable<?>, ? extends Writable> recordReader = (RecordReader<? extends WritableComparable<?>, ? extends Writable>)
                    inputFormat.getRecordReader(fileSplit, jobConf, Reporter.NULL);

            int headerCount = getHeaderCount(schema);
            //  Only skip header rows when the split is at the beginning of the file
            if (start == 0 && headerCount > 0) {
                skipHeader(recordReader, headerCount);
            }

            int footerCount = getFooterCount(schema);
            if (footerCount > 0) {
                recordReader = new FooterAwareRecordReader<>(recordReader, footerCount, jobConf);
            }

            return recordReader;
        }
        catch (IOException e) {
            if (e instanceof TextLineLengthLimitExceededException) {
                throw new TrinoException(HIVE_BAD_DATA, "Line too long in text file: " + path, e);
            }

            throw new TrinoException(HIVE_CANNOT_OPEN_SPLIT, format("Error opening Hive split %s (offset=%s, length=%s) using %s: %s",
                    path,
                    start,
                    length,
                    getInputFormatName(schema),
                    firstNonNull(e.getMessage(), e.getClass().getName())),
                    e);
        }
    }

    private static <K, V> void skipHeader(RecordReader<K, V> reader, int headerCount)
            throws IOException
    {
        K key = reader.createKey();
        V value = reader.createValue();

        while (headerCount > 0) {
            if (!reader.next(key, value)) {
                return;
            }
            headerCount--;
        }
    }

    public static void setReadColumns(Configuration configuration, List<Integer> readHiveColumnIndexes)
    {
        configuration.set(READ_COLUMN_IDS_CONF_STR, Joiner.on(',').join(readHiveColumnIndexes));
        configuration.setBoolean(READ_ALL_COLUMNS, false);
    }

    private static void configureCompressionCodecs(JobConf jobConf)
    {
        // add Airlift LZO and LZOP to head of codecs list so as to not override existing entries
        List<String> codecs = newArrayList(Splitter.on(",").trimResults().omitEmptyStrings().split(jobConf.get("io.compression.codecs", "")));
        if (!codecs.contains(LzoCodec.class.getName())) {
            codecs.add(0, LzoCodec.class.getName());
        }
        if (!codecs.contains(LzopCodec.class.getName())) {
            codecs.add(0, LzopCodec.class.getName());
        }
        jobConf.set("io.compression.codecs", codecs.stream().collect(joining(",")));
    }

    public static Optional<CompressionCodec> getCompressionCodec(TextInputFormat inputFormat, Path file)
    {
        CompressionCodecFactory compressionCodecFactory;

        try {
            compressionCodecFactory = (CompressionCodecFactory) COMPRESSION_CODECS_FIELD.get(inputFormat);
        }
        catch (IllegalAccessException e) {
            throw new TrinoException(GENERIC_INTERNAL_ERROR, "Failed to find compressionCodec for inputFormat: " + inputFormat.getClass().getName(), e);
        }

        if (compressionCodecFactory == null) {
            return Optional.empty();
        }

        return Optional.ofNullable(compressionCodecFactory.getCodec(file));
    }

    public static InputFormat<?, ?> getInputFormat(Configuration configuration, Properties schema, boolean symlinkTarget)
    {
        String inputFormatName = getInputFormatName(schema);
        try {
            JobConf jobConf = toJobConf(configuration);
            configureCompressionCodecs(jobConf);

            Class<? extends InputFormat<?, ?>> inputFormatClass = getInputFormatClass(jobConf, inputFormatName);
            if (symlinkTarget && inputFormatClass == SymlinkTextInputFormat.class) {
                String serde = getDeserializerClassName(schema);
                // LazySimpleSerDe is used by TEXTFILE and SEQUENCEFILE. Default to TEXTFILE
                // per Hive spec (https://hive.apache.org/javadocs/r2.1.1/api/org/apache/hadoop/hive/ql/io/SymlinkTextInputFormat.html)
                if (serde.equals(TEXTFILE.getSerde())) {
                    inputFormatClass = getInputFormatClass(jobConf, TEXTFILE.getInputFormat());
                    return ReflectionUtils.newInstance(inputFormatClass, jobConf);
                }
                for (HiveStorageFormat format : HiveStorageFormat.values()) {
                    if (serde.equals(format.getSerde())) {
                        inputFormatClass = getInputFormatClass(jobConf, format.getInputFormat());
                        return ReflectionUtils.newInstance(inputFormatClass, jobConf);
                    }
                }
                throw new TrinoException(HIVE_UNSUPPORTED_FORMAT, "Unknown SerDe for SymlinkTextInputFormat: " + serde);
            }

            return ReflectionUtils.newInstance(inputFormatClass, jobConf);
        }
        catch (ClassNotFoundException | RuntimeException e) {
            throw new TrinoException(HIVE_UNSUPPORTED_FORMAT, "Unable to create input format " + inputFormatName, e);
        }
    }

    @SuppressWarnings({"unchecked", "RedundantCast"})
    private static Class<? extends InputFormat<?, ?>> getInputFormatClass(JobConf conf, String inputFormatName)
            throws ClassNotFoundException
    {
        // legacy names for Parquet
        if ("parquet.hive.DeprecatedParquetInputFormat".equals(inputFormatName) ||
                "parquet.hive.MapredParquetInputFormat".equals(inputFormatName)) {
            return MapredParquetInputFormat.class;
        }

        Class<?> clazz = conf.getClassByName(inputFormatName);
        return (Class<? extends InputFormat<?, ?>>) clazz.asSubclass(InputFormat.class);
    }

    public static String getInputFormatName(Properties schema)
    {
        String name = schema.getProperty(FILE_INPUT_FORMAT);
        checkCondition(name != null, HIVE_INVALID_METADATA, "Table or partition is missing Hive input format property: %s", FILE_INPUT_FORMAT);
        return name;
    }

    public static long parseHiveDate(String value)
    {
        LocalDateTime date = HIVE_DATE_PARSER.parseLocalDateTime(value);
        if (!date.toLocalTime().equals(LocalTime.MIDNIGHT)) {
            throw new IllegalArgumentException(format("The value should be a whole round date: '%s'", value));
        }
        return Days.daysBetween(EPOCH_DAY, date).getDays();
    }

    public static long parseHiveTimestamp(String value)
    {
        return HIVE_TIMESTAMP_PARSER.parseMillis(value) * MICROSECONDS_PER_MILLISECOND;
    }

    public static boolean isSplittable(InputFormat<?, ?> inputFormat, FileSystem fileSystem, Path path)
    {
        // ORC uses a custom InputFormat but is always splittable
        if (inputFormat.getClass().getSimpleName().equals("OrcInputFormat")) {
            return true;
        }

        // use reflection to get isSplittable method on FileInputFormat
        Method method = null;
        for (Class<?> clazz = inputFormat.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            try {
                method = clazz.getDeclaredMethod("isSplitable", FileSystem.class, Path.class);
                break;
            }
            catch (NoSuchMethodException ignored) {
            }
        }

        if (method == null) {
            return false;
        }
        try {
            method.setAccessible(true);
            return (boolean) method.invoke(inputFormat, fileSystem, path);
        }
        catch (InvocationTargetException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public static StructObjectInspector getTableObjectInspector(Deserializer deserializer)
    {
        try {
            ObjectInspector inspector = deserializer.getObjectInspector();
            checkArgument(inspector.getCategory() == Category.STRUCT, "expected STRUCT: %s", inspector.getCategory());
            return (StructObjectInspector) inspector;
        }
        catch (SerDeException e) {
            throw new RuntimeException(e);
        }
    }

    public static String getDeserializerClassName(Properties schema)
    {
        String name = schema.getProperty(SERIALIZATION_LIB);
        checkCondition(name != null, HIVE_INVALID_METADATA, "Table or partition is missing Hive deserializer property: %s", SERIALIZATION_LIB);
        return name;
    }

    public static Deserializer getDeserializer(Configuration configuration, Properties schema)
    {
        String name = getDeserializerClassName(schema);

        // for collection delimiter, Hive 1.x, 2.x uses "colelction.delim" but Hive 3.x uses "collection.delim"
        // see also https://issues.apache.org/jira/browse/HIVE-16922
        if (name.equals(LAZY_SIMPLE_SERDE_CLASS)) {
            if (schema.containsKey("colelction.delim") && !schema.containsKey(COLLECTION_DELIM)) {
                schema.setProperty(COLLECTION_DELIM, schema.getProperty("colelction.delim"));
            }
        }

        Deserializer deserializer = createDeserializer(getDeserializerClass(name));
        initializeDeserializer(configuration, deserializer, schema);
        return deserializer;
    }

    private static Class<? extends Deserializer> getDeserializerClass(String name)
    {
        if (AVRO_SERDE_CLASS.equals(name)) {
            return TrinoAvroSerDe.class;
        }

        try {
            return Class.forName(name).asSubclass(Deserializer.class);
        }
        catch (ClassNotFoundException e) {
            throw new TrinoException(HIVE_SERDE_NOT_FOUND, "deserializer does not exist: " + name);
        }
        catch (ClassCastException e) {
            throw new RuntimeException("invalid deserializer class: " + name);
        }
    }

    private static Deserializer createDeserializer(Class<? extends Deserializer> clazz)
    {
        try {
            return clazz.getConstructor().newInstance();
        }
        catch (ReflectiveOperationException e) {
            throw new RuntimeException("error creating deserializer: " + clazz.getName(), e);
        }
    }

    private static void initializeDeserializer(Configuration configuration, Deserializer deserializer, Properties schema)
    {
        try {
            configuration = copy(configuration); // Some SerDes (e.g. Avro) modify passed configuration
            deserializer.initialize(configuration, schema);
            validate(deserializer);
        }
        catch (SerDeException | RuntimeException e) {
            throw new RuntimeException("error initializing deserializer: " + deserializer.getClass().getName(), e);
        }
    }

    private static void validate(Deserializer deserializer)
    {
        if (deserializer instanceof AbstractSerDe && !((AbstractSerDe) deserializer).getConfigurationErrors().isEmpty()) {
            throw new RuntimeException("There are configuration errors: " + ((AbstractSerDe) deserializer).getConfigurationErrors());
        }
    }

    public static boolean isHiveNull(byte[] bytes)
    {
        return bytes.length == 2 && bytes[0] == '\\' && bytes[1] == 'N';
    }

    public static void verifyPartitionTypeSupported(String partitionName, Type type)
    {
        if (!isValidPartitionType(type)) {
            throw new TrinoException(NOT_SUPPORTED, format("Unsupported type [%s] for partition: %s", type, partitionName));
        }
    }

    private static boolean isValidPartitionType(Type type)
    {
        return type instanceof DecimalType ||
                BOOLEAN.equals(type) ||
                TINYINT.equals(type) ||
                SMALLINT.equals(type) ||
                INTEGER.equals(type) ||
                BIGINT.equals(type) ||
                REAL.equals(type) ||
                DOUBLE.equals(type) ||
                DATE.equals(type) ||
                TIMESTAMP_MILLIS.equals(type) ||
                type instanceof VarcharType ||
                type instanceof CharType;
    }

    public static NullableValue parsePartitionValue(String partitionName, String value, Type type)
    {
        verifyPartitionTypeSupported(partitionName, type);

        boolean isNull = HIVE_DEFAULT_DYNAMIC_PARTITION.equals(value);

        if (type instanceof DecimalType decimalType) {
            if (isNull) {
                return NullableValue.asNull(decimalType);
            }
            if (decimalType.isShort()) {
                if (value.isEmpty()) {
                    return NullableValue.of(decimalType, 0L);
                }
                return NullableValue.of(decimalType, shortDecimalPartitionKey(value, decimalType, partitionName));
            }
            if (value.isEmpty()) {
                return NullableValue.of(decimalType, Int128.ZERO);
            }
            return NullableValue.of(decimalType, longDecimalPartitionKey(value, decimalType, partitionName));
        }

        if (BOOLEAN.equals(type)) {
            if (isNull) {
                return NullableValue.asNull(BOOLEAN);
            }
            if (value.isEmpty()) {
                return NullableValue.of(BOOLEAN, false);
            }
            return NullableValue.of(BOOLEAN, booleanPartitionKey(value, partitionName));
        }

        if (TINYINT.equals(type)) {
            if (isNull) {
                return NullableValue.asNull(TINYINT);
            }
            if (value.isEmpty()) {
                return NullableValue.of(TINYINT, 0L);
            }
            return NullableValue.of(TINYINT, tinyintPartitionKey(value, partitionName));
        }

        if (SMALLINT.equals(type)) {
            if (isNull) {
                return NullableValue.asNull(SMALLINT);
            }
            if (value.isEmpty()) {
                return NullableValue.of(SMALLINT, 0L);
            }
            return NullableValue.of(SMALLINT, smallintPartitionKey(value, partitionName));
        }

        if (INTEGER.equals(type)) {
            if (isNull) {
                return NullableValue.asNull(INTEGER);
            }
            if (value.isEmpty()) {
                return NullableValue.of(INTEGER, 0L);
            }
            return NullableValue.of(INTEGER, integerPartitionKey(value, partitionName));
        }

        if (BIGINT.equals(type)) {
            if (isNull) {
                return NullableValue.asNull(BIGINT);
            }
            if (value.isEmpty()) {
                return NullableValue.of(BIGINT, 0L);
            }
            return NullableValue.of(BIGINT, bigintPartitionKey(value, partitionName));
        }

        if (DATE.equals(type)) {
            if (isNull) {
                return NullableValue.asNull(DATE);
            }
            return NullableValue.of(DATE, datePartitionKey(value, partitionName));
        }

        if (TIMESTAMP_MILLIS.equals(type)) {
            if (isNull) {
                return NullableValue.asNull(TIMESTAMP_MILLIS);
            }
            return NullableValue.of(TIMESTAMP_MILLIS, timestampPartitionKey(value, partitionName));
        }

        if (REAL.equals(type)) {
            if (isNull) {
                return NullableValue.asNull(REAL);
            }
            if (value.isEmpty()) {
                return NullableValue.of(REAL, (long) floatToRawIntBits(0.0f));
            }
            return NullableValue.of(REAL, floatPartitionKey(value, partitionName));
        }

        if (DOUBLE.equals(type)) {
            if (isNull) {
                return NullableValue.asNull(DOUBLE);
            }
            if (value.isEmpty()) {
                return NullableValue.of(DOUBLE, 0.0);
            }
            return NullableValue.of(DOUBLE, doublePartitionKey(value, partitionName));
        }

        if (type instanceof VarcharType) {
            if (isNull) {
                return NullableValue.asNull(type);
            }
            return NullableValue.of(type, varcharPartitionKey(value, partitionName, type));
        }

        if (type instanceof CharType) {
            if (isNull) {
                return NullableValue.asNull(type);
            }
            return NullableValue.of(type, charPartitionKey(value, partitionName, type));
        }

        if (type instanceof VarbinaryType) {
            if (isNull) {
                return NullableValue.asNull(type);
            }
            return NullableValue.of(type, Slices.utf8Slice(value));
        }

        throw new VerifyException(format("Unhandled type [%s] for partition: %s", type, partitionName));
    }

    /**
     * @deprecated Use {@code instanceof} directly, as that allows variable assignment.
     */
    @Deprecated
    public static boolean isArrayType(Type type)
    {
        return type instanceof ArrayType;
    }

    /**
     * @deprecated Use {@code instanceof} directly, as that allows variable assignment.
     */
    @Deprecated
    public static boolean isMapType(Type type)
    {
        return type instanceof MapType;
    }

    /**
     * @deprecated Use {@code instanceof} directly, as that allows variable assignment.
     */
    @Deprecated
    public static boolean isRowType(Type type)
    {
        return type instanceof RowType;
    }

    public static boolean isStructuralType(Type type)
    {
        return isArrayType(type) || isMapType(type) || isRowType(type);
    }

    public static boolean isStructuralType(HiveType hiveType)
    {
        return hiveType.getCategory() == Category.LIST || hiveType.getCategory() == Category.MAP || hiveType.getCategory() == Category.STRUCT || hiveType.getCategory() == Category.UNION;
    }

    public static boolean booleanPartitionKey(String value, String name)
    {
        if (value.equalsIgnoreCase("true")) {
            return true;
        }
        if (value.equalsIgnoreCase("false")) {
            return false;
        }
        throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for BOOLEAN partition key: %s", value, name));
    }

    public static long bigintPartitionKey(String value, String name)
    {
        try {
            return parseLong(value);
        }
        catch (NumberFormatException e) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for BIGINT partition key: %s", value, name));
        }
    }

    public static long integerPartitionKey(String value, String name)
    {
        try {
            return parseInt(value);
        }
        catch (NumberFormatException e) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for INTEGER partition key: %s", value, name));
        }
    }

    public static long smallintPartitionKey(String value, String name)
    {
        try {
            return parseShort(value);
        }
        catch (NumberFormatException e) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for SMALLINT partition key: %s", value, name));
        }
    }

    public static long tinyintPartitionKey(String value, String name)
    {
        try {
            return parseByte(value);
        }
        catch (NumberFormatException e) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for TINYINT partition key: %s", value, name));
        }
    }

    public static long floatPartitionKey(String value, String name)
    {
        try {
            return floatToRawIntBits(parseFloat(value));
        }
        catch (NumberFormatException e) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for FLOAT partition key: %s", value, name));
        }
    }

    public static double doublePartitionKey(String value, String name)
    {
        try {
            return parseDouble(value);
        }
        catch (NumberFormatException e) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for DOUBLE partition key: %s", value, name));
        }
    }

    public static long datePartitionKey(String value, String name)
    {
        try {
            return parseHiveDate(value);
        }
        catch (IllegalArgumentException e) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for DATE partition key: %s", value, name));
        }
    }

    public static long timestampPartitionKey(String value, String name)
    {
        try {
            return parseHiveTimestamp(value);
        }
        catch (IllegalArgumentException e) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for TIMESTAMP partition key: %s", value, name));
        }
    }

    public static long shortDecimalPartitionKey(String value, DecimalType type, String name)
    {
        return decimalPartitionKey(value, type, name).unscaledValue().longValue();
    }

    public static Int128 longDecimalPartitionKey(String value, DecimalType type, String name)
    {
        return Int128.valueOf(decimalPartitionKey(value, type, name).unscaledValue());
    }

    private static BigDecimal decimalPartitionKey(String value, DecimalType type, String name)
    {
        try {
            if (value.endsWith(BIG_DECIMAL_POSTFIX)) {
                value = value.substring(0, value.length() - BIG_DECIMAL_POSTFIX.length());
            }

            BigDecimal decimal = new BigDecimal(value);
            decimal = decimal.setScale(type.getScale(), UNNECESSARY);
            if (decimal.precision() > type.getPrecision()) {
                throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for %s partition key: %s", value, type, name));
            }
            return decimal;
        }
        catch (NumberFormatException e) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for %s partition key: %s", value, type, name));
        }
    }

    public static Slice varcharPartitionKey(String value, String name, Type columnType)
    {
        Slice partitionKey = Slices.utf8Slice(value);
        VarcharType varcharType = (VarcharType) columnType;
        if (!varcharType.isUnbounded() && SliceUtf8.countCodePoints(partitionKey) > varcharType.getBoundedLength()) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for %s partition key: %s", value, columnType, name));
        }
        return partitionKey;
    }

    public static Slice charPartitionKey(String value, String name, Type columnType)
    {
        Slice partitionKey = trimTrailingSpaces(Slices.utf8Slice(value));
        CharType charType = (CharType) columnType;
        if (SliceUtf8.countCodePoints(partitionKey) > charType.getLength()) {
            throw new TrinoException(HIVE_INVALID_PARTITION_VALUE, format("Invalid partition value '%s' for %s partition key: %s", value, columnType, name));
        }
        return partitionKey;
    }

    public static List<HiveColumnHandle> hiveColumnHandles(Table table, TypeManager typeManager, HiveTimestampPrecision timestampPrecision)
    {
        ImmutableList.Builder<HiveColumnHandle> columns = ImmutableList.builder();

        // add the data fields first
        columns.addAll(getRegularColumnHandles(table, typeManager, timestampPrecision));

        // add the partition keys last (like Hive does)
        columns.addAll(getPartitionKeyColumnHandles(table, typeManager));

        // add hidden columns
        columns.add(pathColumnHandle());
        if (table.getStorage().getBucketProperty().isPresent()) {
            if (isSupportedBucketing(table)) {
                columns.add(bucketColumnHandle());
            }
        }
        columns.add(fileSizeColumnHandle());
        columns.add(fileModifiedTimeColumnHandle());
        if (!table.getPartitionColumns().isEmpty()) {
            columns.add(partitionColumnHandle());
        }

        return columns.build();
    }

    public static List<HiveColumnHandle> getRegularColumnHandles(Table table, TypeManager typeManager, HiveTimestampPrecision timestampPrecision)
    {
        ImmutableList.Builder<HiveColumnHandle> columns = ImmutableList.builder();

        int hiveColumnIndex = 0;
        for (Column field : table.getDataColumns()) {
            // ignore unsupported types rather than failing
            HiveType hiveType = field.getType();
            if (hiveType.isSupportedType(table.getStorage().getStorageFormat())) {
                columns.add(createBaseColumn(field.getName(), hiveColumnIndex, hiveType, hiveType.getType(typeManager, timestampPrecision), REGULAR, field.getComment()));
            }
            hiveColumnIndex++;
        }

        return columns.build();
    }

    public static List<HiveColumnHandle> getPartitionKeyColumnHandles(Table table, TypeManager typeManager)
    {
        ImmutableList.Builder<HiveColumnHandle> columns = ImmutableList.builder();

        List<Column> partitionKeys = table.getPartitionColumns();
        for (Column field : partitionKeys) {
            HiveType hiveType = field.getType();
            if (!hiveType.isSupportedType(table.getStorage().getStorageFormat())) {
                throw new TrinoException(NOT_SUPPORTED, format("Unsupported Hive type %s found in partition keys of table %s.%s", hiveType, table.getDatabaseName(), table.getTableName()));
            }
            columns.add(createBaseColumn(field.getName(), -1, hiveType, hiveType.getType(typeManager), PARTITION_KEY, field.getComment()));
        }

        return columns.build();
    }

    public static void checkCondition(boolean condition, ErrorCodeSupplier errorCode, String formatString, Object... args)
    {
        if (!condition) {
            throw new TrinoException(errorCode, format(formatString, args));
        }
    }

    @Nullable
    public static String columnExtraInfo(boolean partitionKey)
    {
        return partitionKey ? "partition key" : null;
    }

    public static List<String> toPartitionValues(String partitionName)
    {
        // mimics Warehouse.makeValsFromName
        ImmutableList.Builder<String> resultBuilder = ImmutableList.builder();
        int start = 0;
        while (true) {
            while (start < partitionName.length() && partitionName.charAt(start) != '=') {
                start++;
            }
            start++;
            int end = start;
            while (end < partitionName.length() && partitionName.charAt(end) != '/') {
                end++;
            }
            if (start > partitionName.length()) {
                break;
            }
            resultBuilder.add(unescapePathName(partitionName.substring(start, end)));
            start = end + 1;
        }
        return resultBuilder.build();
    }

    public static NullableValue getPrefilledColumnValue(
            HiveColumnHandle columnHandle,
            HivePartitionKey partitionKey,
            Path path,
            OptionalInt bucketNumber,
            long fileSize,
            long fileModifiedTime,
            String partitionName)
    {
        String columnValue;
        if (partitionKey != null) {
            columnValue = partitionKey.getValue();
        }
        else if (isPathColumnHandle(columnHandle)) {
            columnValue = path.toString();
        }
        else if (isBucketColumnHandle(columnHandle)) {
            columnValue = String.valueOf(bucketNumber.getAsInt());
        }
        else if (isFileSizeColumnHandle(columnHandle)) {
            columnValue = String.valueOf(fileSize);
        }
        else if (isFileModifiedTimeColumnHandle(columnHandle)) {
            columnValue = HIVE_TIMESTAMP_PARSER.print(fileModifiedTime);
        }
        else if (isPartitionColumnHandle(columnHandle)) {
            columnValue = partitionName;
        }
        else {
            throw new TrinoException(NOT_SUPPORTED, "unsupported hidden column: " + columnHandle);
        }

        byte[] bytes = columnValue.getBytes(UTF_8);
        String name = columnHandle.getName();
        Type type = columnHandle.getType();
        if (isHiveNull(bytes)) {
            return NullableValue.asNull(type);
        }
        if (type.equals(BOOLEAN)) {
            return NullableValue.of(type, booleanPartitionKey(columnValue, name));
        }
        if (type.equals(BIGINT)) {
            return NullableValue.of(type, bigintPartitionKey(columnValue, name));
        }
        if (type.equals(INTEGER)) {
            return NullableValue.of(type, integerPartitionKey(columnValue, name));
        }
        if (type.equals(SMALLINT)) {
            return NullableValue.of(type, smallintPartitionKey(columnValue, name));
        }
        if (type.equals(TINYINT)) {
            return NullableValue.of(type, tinyintPartitionKey(columnValue, name));
        }
        if (type.equals(REAL)) {
            return NullableValue.of(type, floatPartitionKey(columnValue, name));
        }
        if (type.equals(DOUBLE)) {
            return NullableValue.of(type, doublePartitionKey(columnValue, name));
        }
        if (type instanceof VarcharType) {
            return NullableValue.of(type, varcharPartitionKey(columnValue, name, type));
        }
        if (type instanceof CharType) {
            return NullableValue.of(type, charPartitionKey(columnValue, name, type));
        }
        if (type.equals(DATE)) {
            return NullableValue.of(type, datePartitionKey(columnValue, name));
        }
        if (type.equals(TIMESTAMP_MILLIS)) {
            return NullableValue.of(type, timestampPartitionKey(columnValue, name));
        }
        if (type.equals(TIMESTAMP_TZ_MILLIS)) {
            // used for $file_modified_time
            return NullableValue.of(type, packDateTimeWithZone(floorDiv(timestampPartitionKey(columnValue, name), MICROSECONDS_PER_MILLISECOND), DateTimeZone.getDefault().getID()));
        }
        if (type instanceof DecimalType decimalType) {
            if (decimalType.isShort()) {
                return NullableValue.of(type, shortDecimalPartitionKey(columnValue, decimalType, name));
            }
            return NullableValue.of(type, longDecimalPartitionKey(columnValue, decimalType, name));
        }
        if (type.equals(VarbinaryType.VARBINARY)) {
            return NullableValue.of(type, utf8Slice(columnValue));
        }

        throw new TrinoException(NOT_SUPPORTED, format("Unsupported column type %s for prefilled column: %s", type.getDisplayName(), name));
    }

    public static List<HiveType> extractStructFieldTypes(HiveType hiveType)
    {
        return ((StructTypeInfo) hiveType.getTypeInfo()).getAllStructFieldTypeInfos().stream()
                .map(typeInfo -> HiveType.valueOf(typeInfo.getTypeName()))
                .collect(toImmutableList());
    }

    public static int getHeaderCount(Properties schema)
    {
        return getPositiveIntegerValue(schema, SKIP_HEADER_COUNT_KEY, "0");
    }

    public static int getFooterCount(Properties schema)
    {
        return getPositiveIntegerValue(schema, SKIP_FOOTER_COUNT_KEY, "0");
    }

    private static int getPositiveIntegerValue(Properties schema, String key, String defaultValue)
    {
        String value = schema.getProperty(key, defaultValue);
        try {
            int intValue = parseInt(value);
            if (intValue < 0) {
                throw new TrinoException(HIVE_INVALID_METADATA, format("Invalid value for %s property: %s", key, value));
            }
            return intValue;
        }
        catch (NumberFormatException e) {
            throw new TrinoException(HIVE_INVALID_METADATA, format("Invalid value for %s property: %s", key, value));
        }
    }

    public static List<String> getColumnNames(Properties schema)
    {
        return COLUMN_NAMES_SPLITTER.splitToList(schema.getProperty(LIST_COLUMNS, ""));
    }

    public static List<HiveType> getColumnTypes(Properties schema)
    {
        return toHiveTypes(schema.getProperty(LIST_COLUMN_TYPES, ""));
    }

    public static OrcWriterOptions getOrcWriterOptions(Properties schema, OrcWriterOptions orcWriterOptions)
    {
        if (schema.containsKey(ORC_BLOOM_FILTER_COLUMNS_KEY)) {
            if (!schema.containsKey(ORC_BLOOM_FILTER_FPP_KEY)) {
                throw new TrinoException(HIVE_INVALID_METADATA, "FPP for bloom filter is missing");
            }
            try {
                double fpp = parseDouble(schema.getProperty(ORC_BLOOM_FILTER_FPP_KEY));
                return orcWriterOptions
                        .withBloomFilterColumns(ImmutableSet.copyOf(COLUMN_NAMES_SPLITTER.splitToList(schema.getProperty(ORC_BLOOM_FILTER_COLUMNS_KEY))))
                        .withBloomFilterFpp(fpp);
            }
            catch (NumberFormatException e) {
                throw new TrinoException(HIVE_UNSUPPORTED_FORMAT, format("Invalid value for %s property: %s", ORC_BLOOM_FILTER_FPP, schema.getProperty(ORC_BLOOM_FILTER_FPP_KEY)));
            }
        }
        return orcWriterOptions;
    }

    public static SortingColumn sortingColumnFromString(String name)
    {
        SortingColumn.Order order = ASCENDING;
        String lower = name.toUpperCase(ENGLISH);
        if (lower.endsWith(" ASC")) {
            name = name.substring(0, name.length() - 4).trim();
        }
        else if (lower.endsWith(" DESC")) {
            name = name.substring(0, name.length() - 5).trim();
            order = DESCENDING;
        }
        return new SortingColumn(name, order);
    }

    public static String sortingColumnToString(SortingColumn column)
    {
        return column.getColumnName() + ((column.getOrder() == DESCENDING) ? " DESC" : "");
    }

    public static boolean isHiveSystemSchema(String schemaName)
    {
        if ("information_schema".equals(schemaName)) {
            // For things like listing columns in information_schema.columns table, we need to explicitly filter out Hive's own information_schema.
            // TODO https://github.com/trinodb/trino/issues/1559 this should be filtered out in engine.
            return true;
        }
        if ("sys".equals(schemaName)) {
            // Hive 3's `sys` schema contains no objects we can handle, so there is no point in exposing it.
            // Also, exposing it may require proper handling in access control.
            return true;
        }
        return false;
    }

    public static boolean isDeltaLakeTable(Table table)
    {
        return isDeltaLakeTable(table.getParameters());
    }

    public static boolean isDeltaLakeTable(Map<String, String> tableParameters)
    {
        return tableParameters.containsKey(SPARK_TABLE_PROVIDER_KEY)
                && tableParameters.get(SPARK_TABLE_PROVIDER_KEY).toLowerCase(ENGLISH).equals(DELTA_LAKE_PROVIDER);
    }

    public static boolean isIcebergTable(Table table)
    {
        return isIcebergTable(table.getParameters());
    }

    public static boolean isIcebergTable(Map<String, String> tableParameters)
    {
        return ICEBERG_TABLE_TYPE_VALUE.equalsIgnoreCase(tableParameters.get(ICEBERG_TABLE_TYPE_NAME));
    }

    public static boolean isHudiTable(Table table)
    {
        requireNonNull(table, "table is null");
        String inputFormat = table.getStorage().getStorageFormat().getInputFormat();

        return HUDI_PARQUET_INPUT_FORMAT.equals(inputFormat) ||
                HUDI_PARQUET_REALTIME_INPUT_FORMAT.equals(inputFormat) ||
                HUDI_INPUT_FORMAT.equals(inputFormat) ||
                HUDI_REALTIME_INPUT_FORMAT.equals(inputFormat);
    }

    public static boolean isSparkBucketedTable(Table table)
    {
        return table.getParameters().containsKey(SPARK_TABLE_PROVIDER_KEY)
                && table.getParameters().containsKey(SPARK_TABLE_BUCKET_NUMBER_KEY);
    }

    public static Function<HiveColumnHandle, ColumnMetadata> columnMetadataGetter(Table table)
    {
        ImmutableList.Builder<String> columnNames = ImmutableList.builder();
        table.getPartitionColumns().stream().map(Column::getName).forEach(columnNames::add);
        table.getDataColumns().stream().map(Column::getName).forEach(columnNames::add);
        List<String> allColumnNames = columnNames.build();
        if (allColumnNames.size() > Sets.newHashSet(allColumnNames).size()) {
            throw new TrinoException(HIVE_INVALID_METADATA,
                    format("Hive metadata for table %s is invalid: Table descriptor contains duplicate columns", table.getTableName()));
        }

        List<Column> tableColumns = table.getDataColumns();
        ImmutableMap.Builder<String, Optional<String>> builder = ImmutableMap.builder();
        for (Column field : concat(tableColumns, table.getPartitionColumns())) {
            if (field.getComment().isPresent() && !field.getComment().get().equals("from deserializer")) {
                builder.put(field.getName(), field.getComment());
            }
            else {
                builder.put(field.getName(), Optional.empty());
            }
        }

        Map<String, Optional<String>> columnComment = builder.buildOrThrow();

        return handle -> ColumnMetadata.builder()
                .setName(handle.getName())
                .setType(handle.getType())
                .setComment(handle.isHidden() ? Optional.empty() : columnComment.get(handle.getName()))
                .setExtraInfo(Optional.ofNullable(columnExtraInfo(handle.isPartitionKey())))
                .setHidden(handle.isHidden())
                .build();
    }

    // copy of org.apache.hadoop.hive.common.FileUtils#unescapePathName
    @SuppressWarnings("NumericCastThatLosesPrecision")
    public static String unescapePathName(String path)
    {
        // fast path, no escaped characters and therefore no copying necessary
        int escapedAtIndex = path.indexOf('%');
        if (escapedAtIndex < 0 || escapedAtIndex + 2 >= path.length()) {
            return path;
        }

        // slow path, unescape into a new string copy
        StringBuilder sb = new StringBuilder();
        int fromIndex = 0;
        while (escapedAtIndex >= 0 && escapedAtIndex + 2 < path.length()) {
            // preceding sequence without escaped characters
            if (escapedAtIndex > fromIndex) {
                sb.append(path, fromIndex, escapedAtIndex);
            }
            // try to parse the to digits after the percent sign as hex
            try {
                int code = HexFormat.fromHexDigits(path, escapedAtIndex + 1, escapedAtIndex + 3);
                sb.append((char) code);
                // advance past the percent sign and both hex digits
                fromIndex = escapedAtIndex + 3;
            }
            catch (NumberFormatException e) {
                // invalid escape sequence, only advance past the percent sign
                sb.append('%');
                fromIndex = escapedAtIndex + 1;
            }
            // find next escaped character
            escapedAtIndex = path.indexOf('%', fromIndex);
        }
        // trailing sequence without escaped characters
        if (fromIndex < path.length()) {
            sb.append(path, fromIndex, path.length());
        }
        return sb.toString();
    }

    // copy of org.apache.hadoop.hive.common.FileUtils#escapePathName
    public static String escapePathName(String path)
    {
        if (isNullOrEmpty(path)) {
            return HIVE_DEFAULT_DYNAMIC_PARTITION;
        }

        //  Fast-path detection, no escaping and therefore no copying necessary
        int escapeAtIndex = PATH_CHAR_TO_ESCAPE.indexIn(path);
        if (escapeAtIndex < 0) {
            return path;
        }

        // slow path, escape beyond the first required escape character into a new string
        StringBuilder sb = new StringBuilder();
        int fromIndex = 0;
        while (escapeAtIndex >= 0 && escapeAtIndex < path.length()) {
            // preceding characters without escaping needed
            if (escapeAtIndex > fromIndex) {
                sb.append(path, fromIndex, escapeAtIndex);
            }
            // escape single character
            char c = path.charAt(escapeAtIndex);
            sb.append('%').append(HEX_UPPER_FORMAT.toHighHexDigit(c)).append(HEX_UPPER_FORMAT.toLowHexDigit(c));
            // find next character to escape
            fromIndex = escapeAtIndex + 1;
            if (fromIndex < path.length()) {
                escapeAtIndex = PATH_CHAR_TO_ESCAPE.indexIn(path, fromIndex);
            }
            else {
                escapeAtIndex = -1;
            }
        }
        // trailing characters without escaping needed
        if (fromIndex < path.length()) {
            sb.append(path, fromIndex, path.length());
        }
        return sb.toString();
    }

    // copy of org.apache.hadoop.hive.common.FileUtils#makePartName
    public static String makePartName(List<String> columns, List<String> values)
    {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                name.append('/');
            }
            name.append(escapePathName(columns.get(i).toLowerCase(ENGLISH)));
            name.append('=');
            name.append(escapePathName(values.get(i)));
        }
        return name.toString();
    }
}
