/*
 * Copyright (c) 2014-2015, Bolotin Dmitry, Chudakov Dmitry, Shugay Mikhail
 * (here and after addressed as Inventors)
 * All Rights Reserved
 *
 * Permission to use, copy, modify and distribute any part of this program for
 * educational, research and non-profit purposes, by non-profit institutions
 * only, without fee, and without a written agreement is hereby granted,
 * provided that the above copyright notice, this paragraph and the following
 * three paragraphs appear in all copies.
 *
 * Those desiring to incorporate this work into commercial products or use for
 * commercial purposes should contact the Inventors using one of the following
 * email addresses: chudakovdm@mail.ru, chudakovdm@gmail.com
 *
 * IN NO EVENT SHALL THE INVENTORS BE LIABLE TO ANY PARTY FOR DIRECT, INDIRECT,
 * SPECIAL, INCIDENTAL, OR CONSEQUENTIAL DAMAGES, INCLUDING LOST PROFITS,
 * ARISING OUT OF THE USE OF THIS SOFTWARE, EVEN IF THE INVENTORS HAS BEEN
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 * THE SOFTWARE PROVIDED HEREIN IS ON AN "AS IS" BASIS, AND THE INVENTORS HAS
 * NO OBLIGATION TO PROVIDE MAINTENANCE, SUPPORT, UPDATES, ENHANCEMENTS, OR
 * MODIFICATIONS. THE INVENTORS MAKES NO REPRESENTATIONS AND EXTENDS NO
 * WARRANTIES OF ANY KIND, EITHER IMPLIED OR EXPRESS, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY OR FITNESS FOR A
 * PARTICULAR PURPOSE, OR THAT THE USE OF THE SOFTWARE WILL NOT INFRINGE ANY
 * PATENT, TRADEMARK OR OTHER RIGHTS.
 */
package com.milaboratory.mixcr.basictypes;

import com.milaboratory.core.alignment.Alignment;
import com.milaboratory.core.sequence.NucleotideSequence;
import com.milaboratory.mixcr.reference.Allele;
import com.milaboratory.mixcr.reference.GeneFeature;
import com.milaboratory.mixcr.reference.GeneType;
import com.milaboratory.mixcr.reference.ReferencePoint;
import com.milaboratory.primitivio.annotations.Serializable;

import java.util.Arrays;

@Serializable(by = IO.VDJCHitSerializer.class)
public final class VDJCHit implements Comparable<VDJCHit> {
    private final Allele allele;
    private final Alignment<NucleotideSequence>[] alignments;
    private final GeneFeature alignedFeature;
    private final float score;

    public VDJCHit(Allele allele, Alignment<NucleotideSequence> alignments, GeneFeature alignedFeature) {
        this(allele, new Alignment[]{alignments}, alignedFeature);
    }

    public VDJCHit(Allele allele, Alignment<NucleotideSequence>[] alignments, GeneFeature alignedFeature) {
        assert (alignments[0] == null || allele.getFeature(alignedFeature).equals(alignments[0].getSequence1()));
        assert (alignments.length < 2 || alignments[1] == null || allele.getFeature(alignedFeature).equals(alignments[1].getSequence1()));
        this.allele = allele;
        this.alignments = alignments;
        this.alignedFeature = alignedFeature;

        float sum = 0.0f;
        for (Alignment<NucleotideSequence> alignment : alignments)
            if (alignment != null)
                sum += alignment.getScore();
        this.score = sum;
    }

    public VDJCHit(Allele allele, Alignment<NucleotideSequence>[] alignments, GeneFeature alignedFeature, float score) {
        assert (alignments[0] == null || allele.getFeature(alignedFeature).equals(alignments[0].getSequence1()));
        assert (alignments.length < 2 || alignments[1] == null || allele.getFeature(alignedFeature).equals(alignments[1].getSequence1()));
        this.allele = allele;
        this.alignments = alignments;
        this.alignedFeature = alignedFeature;
        this.score = score;
    }

    public int getPosition(int target, ReferencePoint referencePoint) {
        if (alignments[target] == null)
            return -1;
        int positionInAllele = allele.getPartitioning().getRelativePosition(alignedFeature, referencePoint);
        if (positionInAllele == -1)
            return -1;
        return alignments[target].convertPosition(positionInAllele);
    }

    public GeneType getGeneType() {
        return allele.getGeneType();
    }

    public float getScore() {
        return score;
    }

    public Allele getAllele() {
        return allele;
    }

    public GeneFeature getAlignedFeature() {
        return alignedFeature;
    }

    public Alignment<NucleotideSequence> getAlignment(int target) {
        return alignments[target];
    }

    public int numberOfTargets() {
        return alignments.length;
    }

    public TargetPartitioning getPartitioningForTarget(int targetIndex) {
        return new TargetPartitioning(targetIndex, this);
    }

    @Override
    public String toString() {
        return allele.getName() + "[" + score + "]";
    }

    @Override
    public int compareTo(VDJCHit o) {
        return Float.compare(o.score, score);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof VDJCHit)) return false;

        VDJCHit vdjcHit = (VDJCHit) o;

        if (Float.compare(vdjcHit.score, score) != 0) return false;
        if (!alignedFeature.equals(vdjcHit.alignedFeature)) return false;
        if (!Arrays.equals(alignments, vdjcHit.alignments)) return false;
        if (!allele.getId().equals(vdjcHit.allele.getId())) return false;

        return true;
    }

    @Override
    public int hashCode() {
        int result = allele.getId().hashCode();
        result = 31 * result + Arrays.hashCode(alignments);
        result = 31 * result + alignedFeature.hashCode();
        result = 31 * result + (score != +0.0f ? Float.floatToIntBits(score) : 0);
        return result;
    }
}
