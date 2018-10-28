package org.apache.spark.network.shuffle;import com.google.common.io.Closeables;import org.apache.spark.network.shuffle.protocol.ExecutorShuffleInfo;import org.apache.spark.network.shuffle.protocol.UploadBlockIndex;import org.apache.spark.network.util.JavaUtils;import org.apache.spark.storage.TempShuffleBlockId;import org.apache.spark.util.Utils;import org.slf4j.Logger;import org.slf4j.LoggerFactory;import scala.Tuple2;import java.io.File;import java.io.FileInputStream;import java.io.FileOutputStream;import java.io.IOException;import java.nio.channels.FileChannel;import java.util.UUID;/** * \* Created with IntelliJ IDEA. * \* User: weixiuli * \* Date: 2018/10/27 * \* Time: 上午11:31 * \* To change this template use File | Settings | File Templates. * \* Description: * \ */public class ExternalShuffleUtils {    private static final Logger logger = LoggerFactory.getLogger(ExternalShuffleUtils.class);    /**     * Merge zero or more spill files together, choosing the fastest merging strategy based on the     * number of spills and the IO compression codec.     *     * @return the partition lengths in the merged file.     */    public static long[] mergeSpills(SpillInfo[] spills, File outputFile, int numPartitions) throws IOException {        try {            if (spills.length == 0) {                new FileOutputStream(outputFile).close(); // Create an empty file                return new long[numPartitions];            } else if (spills.length == 1) {                // Here, we don't need to perform any metrics updates because the bytes written to this                // output file would have already been counted as shuffle bytes written.//                Files.move(spills[0].file, outputFile);                if (!spills[0].file.renameTo(outputFile)) {                    throw new IOException("fail to rename file " + spills[0].file + " to " + outputFile);                }                System.out.format("--------------NOSPILL--------------\n");                return spills[0].partitionLengths;            } else {                final long[] partitionLengths;                // There are multiple spills to merge, so none of these spill files' lengths were counted                // towards our shuffle write count or shuffle write time. If we use the slow merge path,                // then the final output file's size won't necessarily be equal to the sum of the spill                // files' sizes. To guard against this case, we look at the output file's actual size when                // computing shuffle bytes written.                //                // We allow the individual merge methods to report their own IO times since different merge                // strategies use different IO techniques.  We count IO during merge towards the shuffle                // shuffle write time, which appears to be consistent with the "not bypassing merge-sort"                // branch in ExternalSorter.                // Compression is disabled or we are using an IO compression codec that supports                // decompression of concatenated compressed streams, so we can perform a fast spill merge                logger.debug("Using transferTo-based fast merge");                partitionLengths = mergeSpillsWithTransferTo(spills, outputFile, numPartitions);                // When closing an UnsafeShuffleExternalSorter that has already spilled once but also has                // in-memory records, we write out the in-memory records to a file but do not count that                // final write as bytes spilled (instead, it's accounted as shuffle write). The merge needs                // to be counted as shuffle write, but this will lead to double-counting of the final                // SpillInfo's bytes.                System.out.format("--------------SPILL--------------\n");                return partitionLengths;            }        } catch (IOException e) {            if (outputFile.exists() && !outputFile.delete()) {                logger.error("Unable to delete output file {}", outputFile.getPath());            }            throw e;        }    }    /**     * Merges spill files by using NIO's transferTo to concatenate spill partitions' bytes.     * This is only safe when the IO compression codec and serializer support concatenation of     * serialized streams.     *     * @return the partition lengths in the merged file.     */    public static long[] mergeSpillsWithTransferTo(SpillInfo[] spills, File outputFile, int numPartitions) throws IOException {        assert (spills.length >= 2);        final long[] partitionLengths = new long[numPartitions];        final FileChannel[] spillInputChannels = new FileChannel[spills.length];        final long[] spillInputChannelPositions = new long[spills.length];        FileChannel mergedFileOutputChannel = null;        boolean threwException = true;        try {            for (int i = 0; i < spills.length; i++) {                spillInputChannels[i] = new FileInputStream(spills[i].file).getChannel();            }            // This file needs to opened in append mode in order to work around a Linux kernel bug that            // affects transferTo; see SPARK-3948 for more details.            mergedFileOutputChannel = new FileOutputStream(outputFile, true).getChannel();            long bytesWrittenToMergedFile = 0;            for (int partition = 0; partition < numPartitions; partition++) {                for (int i = 0; i < spills.length; i++) {                    final long partitionLengthInSpill = spills[i].partitionLengths[partition];                    final FileChannel spillInputChannel = spillInputChannels[i];                    Utils.copyFileStreamNIO(                            spillInputChannel,                            mergedFileOutputChannel,                            spillInputChannelPositions[i],                            partitionLengthInSpill);                    spillInputChannelPositions[i] += partitionLengthInSpill;                    bytesWrittenToMergedFile += partitionLengthInSpill;                    partitionLengths[partition] += partitionLengthInSpill;                }            }            // Check the position after transferTo loop to see if it is in the right position and raise an            // exception if it is incorrect. The position will not be increased to the expected length            // after calling transferTo in kernel version 2.6.32. This issue is described at            // https://bugs.openjdk.java.net/browse/JDK-7052359 and SPARK-3948.            if (mergedFileOutputChannel.position() != bytesWrittenToMergedFile) {                throw new IOException(                        "Current position " + mergedFileOutputChannel.position() + " does not equal expected " +                                "position " + bytesWrittenToMergedFile + " after transferTo. Please check your kernel" +                                " version to see if it is 2.6.32, as there is a kernel bug which will lead to " +                                "unexpected behavior when using transferTo. You can set spark.file.transferTo=false " +                                "to disable this NIO feature."                );            }            threwException = false;        } finally {            // To avoid masking exceptions that caused us to prematurely enter the finally block, only            // throw exceptions during cleanup if threwException == false.            for (int i = 0; i < spills.length; i++) {                assert (spillInputChannelPositions[i] == spills[i].file.length());                Closeables.close(spillInputChannels[i], threwException);            }            Closeables.close(mergedFileOutputChannel, threwException);        }        return partitionLengths;    }    /**     * Produces a unique block id and File suitable for storing shuffled intermediate results.     */    public static Tuple2<TempShuffleBlockId, File> createTempShuffleBlock(ExecutorShuffleInfo execInfo, String filename) {        TempShuffleBlockId blockId = new TempShuffleBlockId(UUID.randomUUID());        while (getFile(execInfo, filename).exists()) {            blockId = new TempShuffleBlockId(UUID.randomUUID());        }        return new Tuple2(blockId, getFile(execInfo, filename));    }    public static File getFile(ExecutorShuffleInfo execInfo, String filename) {        int hash = JavaUtils.nonNegativeHash(filename);        int dirId = hash % execInfo.localDirs.length;        int subDirId = (hash / execInfo.localDirs.length) % execInfo.subDirsPerLocalDir;        // Create the subdirectory if it doesn't already exist        File newDir = new File(execInfo.localDirs[dirId], String.format("%02x", subDirId));        if (!newDir.exists() && !newDir.mkdir()) {            logger.error("Failed to create local dir in $newDir");        }        return new File(newDir, filename);    }    public static String getAppExecIdBlockID(UploadBlockIndex msg) {        return msg.appId + "_" + msg.execId + "_" + msg.blockId;    }}