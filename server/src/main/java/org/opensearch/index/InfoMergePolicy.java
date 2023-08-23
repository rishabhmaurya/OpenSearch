/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.index;

import org.apache.logging.log4j.Logger;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.FilterMergePolicy;
import org.apache.lucene.index.MergePolicy;
import org.apache.lucene.index.MergeTrigger;
import org.apache.lucene.index.SegmentCommitInfo;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.util.IOSupplier;

import java.io.IOException;
import java.util.Map;

public class InfoMergePolicy extends FilterMergePolicy {
    MergePolicy in;
    private final Logger logger;
    /**
     * Creates a new filter merge policy instance wrapping another.
     *
     * @param in the wrapped {@link MergePolicy}
     */
    public InfoMergePolicy(Logger logger, MergePolicy in) {
        super(in);
        this.logger = logger;
        this.in = in;
    }

    @Override
    public MergeSpecification findMerges(
        MergeTrigger mergeTrigger, SegmentInfos segmentInfos, MergeContext mergeContext)
        throws IOException {
        MergeSpecification spec =  in.findMerges(mergeTrigger, segmentInfos, mergeContext);
        printInfo(spec);
        return spec;
    }

    @Override
    public MergeSpecification findMerges(CodecReader... readers) throws IOException {
       MergeSpecification spec = in.findMerges(readers);
        printInfo(spec);
        return spec;
    }

    @Override
    public MergeSpecification findForcedMerges(
        SegmentInfos segmentInfos,
        int maxSegmentCount,
        Map<SegmentCommitInfo, Boolean> segmentsToMerge,
        MergeContext mergeContext)
        throws IOException {
        MergeSpecification spec =  in.findForcedMerges(segmentInfos, maxSegmentCount, segmentsToMerge, mergeContext);
        printInfo(spec);
        return spec;
    }

    @Override
    public MergeSpecification findForcedDeletesMerges(
        SegmentInfos segmentInfos, MergeContext mergeContext) throws IOException {
        MergeSpecification spec =  in.findForcedDeletesMerges(segmentInfos, mergeContext);
        printInfo(spec);
        return spec;
    }

    @Override
    public MergeSpecification findFullFlushMerges(
        MergeTrigger mergeTrigger, SegmentInfos segmentInfos, MergeContext mergeContext)
        throws IOException {
        MergeSpecification spec =  in.findFullFlushMerges(mergeTrigger, segmentInfos, mergeContext);
        printInfo(spec);
        return spec;
    }

    @Override
    public boolean useCompoundFile(
        SegmentInfos infos, SegmentCommitInfo mergedInfo, MergeContext mergeContext)
        throws IOException {
        return in.useCompoundFile(infos, mergedInfo, mergeContext);
    }

    @Override
    public double getNoCFSRatio() {
        return in.getNoCFSRatio();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + in + ")";
    }

    @Override
    public boolean keepFullyDeletedSegment(IOSupplier<CodecReader> readerIOSupplier)
        throws IOException {
        return in.keepFullyDeletedSegment(readerIOSupplier);
    }

    @Override
    public int numDeletesToMerge(
        SegmentCommitInfo info, int delCount, IOSupplier<CodecReader> readerSupplier)
        throws IOException {
        return in.numDeletesToMerge(info, delCount, readerSupplier);
    }

    @Override
    public MergePolicy unwrap() {
        return in;
    }

    private void printInfo(MergeSpecification spec) throws IOException {
        if (spec == null) {
            return;
        }
        int newMaxDoc = 0;
        double newSize = 0;
        for (OneMerge oneMerge : spec.merges) {
            if (oneMerge.segments.size() > 0) {
                for (SegmentCommitInfo sci : oneMerge.segments) {
                    int numLiveDocs = sci.info.maxDoc() - sci.getDelCount();
                    newSize += (double) sci.sizeInBytes() * numLiveDocs / sci.info.maxDoc();
                    newMaxDoc += numLiveDocs;
                }
            }
        }
        logger.info("Merge Info: Estimated Merge Bytes written : " + newSize);
    }
}
