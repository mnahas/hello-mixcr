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
package com.milaboratory.mixcr.reference.builder;

import cc.redberry.pipe.CUtils;
import com.milaboratory.core.Range;
import com.milaboratory.core.alignment.Aligner;
import com.milaboratory.core.alignment.Alignment;
import com.milaboratory.core.alignment.MultiAlignmentHelper;
import com.milaboratory.core.io.sequence.fasta.FastaReader;
import com.milaboratory.core.mutations.Mutations;
import com.milaboratory.core.mutations.MutationsBuilder;
import com.milaboratory.core.mutations.MutationsUtil;
import com.milaboratory.core.sequence.*;
import com.milaboratory.mixcr.basictypes.SequencePartitioning;
import com.milaboratory.mixcr.basictypes.VDJCAlignmentsFormatter;
import com.milaboratory.mixcr.reference.*;
import com.milaboratory.util.StringUtil;
import gnu.trove.map.TObjectIntMap;

import java.io.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.milaboratory.core.sequence.AminoAcidSequence.convertNtPositionToAA;
import static com.milaboratory.core.sequence.AminoAcidSequence.getTriplet;
import static com.milaboratory.core.sequence.TranslationParameters.withIncompleteCodon;
import static com.milaboratory.mixcr.reference.builder.BuilderUtils.*;
import static com.milaboratory.mixcr.reference.builder.FastaLocusBuilderParameters.AnchorPointPositionInfo.*;

/**
 * Builds MiXCR format LociLibrary file from
 */
public class FastaLocusBuilder {
    public static final int LINE_WIDTH = 80;

    /**
     * Target locus
     */
    private final Locus locus;
    /**
     * Parameters
     */
    private final FastaLocusBuilderParameters parameters;
    /**
     * Genes by name
     */
    private final Map<String, GeneInfo> genes = new HashMap<>();
    /**
     * Stream for logging warning and error messages
     */
    private PrintStream loggingStream = System.out;
    /**
     * Stream for writing final report to
     */
    private PrintStream finalReportStream = System.out;
    /**
     * Determines whether builder should terminate on error
     */
    private boolean errorsAndWarningsToSTDOUT = false;
    /**
     * Determines whether builder should terminate on error
     */
    private boolean exceptionOnError = true;
    /**
     * Determines whether builder should process alleles with non-standard names
     */
    private boolean allowNonStandardAlleleNames = false;

    public FastaLocusBuilder(Locus locus, FastaLocusBuilderParameters parameters) {
        if (locus == null || parameters == null)
            throw new NullPointerException();
        this.locus = locus;
        this.parameters = parameters;
    }

    public FastaLocusBuilder setLoggingStream(PrintStream loggingStream) {
        this.loggingStream = loggingStream;
        return this;
    }

    public FastaLocusBuilder setFinalReportStream(PrintStream finalReportStream) {
        this.finalReportStream = finalReportStream;
        return this;
    }

    public FastaLocusBuilder noExceptionOnError() {
        this.exceptionOnError = false;
        return this;
    }

    public FastaLocusBuilder printErrorsAndWarningsToSTDOUT() {
        this.errorsAndWarningsToSTDOUT = true;
        return this;
    }

    public FastaLocusBuilder allowNonStandardAlleleNames() {
        this.allowNonStandardAlleleNames = true;
        return this;
    }

    private void errorOrException(String message) {
        if (exceptionOnError)
            throw new FastaLocusBuilderException(message);
        else
            error(message);
    }

    private void error(String line) {
        String msg = "Error: " + line;
        if (errorsAndWarningsToSTDOUT && loggingStream != System.out)
            System.out.println(msg);
        log(msg);
    }

    private void warning(String line) {
        String msg = "Warning: " + line;
        if (errorsAndWarningsToSTDOUT && loggingStream != System.out)
            System.out.println(msg);
        log(msg);
    }

    private void log(String line) {
        if (loggingStream != null)
            loggingStream.println(line);
    }

    private void reportLine(String line) {
        if (finalReportStream != null)
            finalReportStream.println(line);
    }

    public void importAllelesFromFile(String fileName) throws IOException {
        try (InputStream stream = new BufferedInputStream(new FileInputStream(fileName))) {
            importAllelesFromStream(stream);
        }
    }

    public void importAllelesFromStream(InputStream stream) {
        // Saving in local variables for compactness of extraction code
        Pattern alleleNameExtractionPattern = parameters.getAlleleNameExtractionPattern();
        Pattern functionalGenePattern = parameters.getFunctionalAllelePattern();
        Pattern referenceAllelePattern = parameters.getReferenceAllelePattern();
        int[] referencePointPositions = parameters.getReferencePointPositions();

        for (FastaReader.RawFastaRecord rec :
                CUtils.it(new FastaReader<>(stream, null).asRawRecordsPort())) {
            //Extracting allele name from header
            Matcher matcher = alleleNameExtractionPattern.matcher(rec.description);
            String alleleName;
            if (matcher.find())
                alleleName = matcher.group(1);
            else {
                String errorMessage = "Header does'n contain allele name pattern: " + rec.description;
                errorOrException(errorMessage);
                continue;
            }

            // Parsing allele name
            matcher = ALLELE_NAME_PATTERN.matcher(alleleName);

            String geneName;

            // Checking
            if (matcher.matches()) {
                // Extracting gene name
                geneName = matcher.group(GENE_NAME_GROUP);

                // Checking locus decoded from allele name
                if (!checkLocus(matcher.group(LOCUS_GROUP)))
                    warning("Allele from different locus(?): " + alleleName);

                // Checking gene type decoded from allele name
                if (Character.toUpperCase(matcher.group(GENE_TYPE_LETTER_GROUP).charAt(0)) !=
                        parameters.getGeneType().getLetter())
                    warning("Allele of different gene type(?): " + alleleName);
            } else {
                String errorMessage = "Allele name doesn't match standard pattern: " + alleleName;
                if (!allowNonStandardAlleleNames)
                    throw new FastaLocusBuilderException(errorMessage);
                else {
                    geneName = alleleName;
                    alleleName += "*00";
                    errorMessage += ". Changed to: " + alleleName;
                    error(errorMessage);
                }
            }

            // Detecting whether allele is functional
            boolean isFunctional = functionalGenePattern.matcher(rec.description).find();

            // Parsing sequence
            StringWithMapping seqWithPositionMapping = StringWithMapping.removeSymbol(rec.sequence, parameters.getPaddingChar());
            NucleotideSequence seq = new NucleotideSequence(seqWithPositionMapping.getModifiedString());

            // If sequence contain wildcards, skip it
            if (seq.containsWildcards()) {
                warning("Skipping " + alleleName + " because it's sequence contains wildcards.");
                continue;
            }

            // Calculating reference points
            int[] referencePoints = new int[referencePointPositions.length];
            for (int i = 0; i < referencePointPositions.length; i++)
                referencePoints[i] = getPosition(seqWithPositionMapping, i);

            boolean isFirst = false, isReference;

            // Adding allele to corresponding gene
            GeneInfo gene;
            if ((gene = genes.get(geneName)) == null) {
                // If gene doesn't exist - create it
                genes.put(geneName, gene = new GeneInfo(geneName));
                // This allele is first for this gene
                isFirst = true;
            }

            // Calculating isReference flag
            if (referenceAllelePattern == null)
                isReference = isFirst;
            else
                isReference = referenceAllelePattern.matcher(rec.description).find();

            // Creating allele info
            AlleleInfo alleleInfo = new AlleleInfo(geneName, alleleName, seq, isFunctional,
                    isReference, referencePoints);

            // Checking conserved amino-acids:
            checkAllele(alleleInfo);

            // Checking if this allele is unique
            if (gene.alleles.containsKey(alleleName)) {
                errorOrException("Duplicate records for allele " + alleleName);
                continue;
            }

            // Adding allele to gene
            gene.alleles.put(alleleName, alleleInfo);

            // Calculating severalReferenceAlleles flag
            if (alleleInfo.isReference && gene.reference != null && gene.reference.isReference)
                gene.severalReferenceAlleles = true;

            // If allele is first for tis gene, add it to reference slot
            // Also reset this slot for first "reference" allele occurred
            if (isFirst || (!gene.reference.isReference && alleleInfo.isReference))
                gene.reference = alleleInfo;
        }
    }

    private int getPosition(StringWithMapping seqWithPositionMapping, int refPointIndex) {
        int referencePointPosition = parameters.getReferencePointPositions()[refPointIndex];

        // Return -1 if information about this reference point is absent
        if (referencePointPosition == -1)
            return -1;

        // Convert references to the beginning or to the end of the sequence
        if (referencePointPosition == BEGINNING_OF_SEQUENCE)
            return 0;
        if (referencePointPosition == END_OF_SEQUENCE)
            return seqWithPositionMapping.getModifiedString().length();

        if (referencePointPosition <= PATTERN_LINK_OFFSET) {
            FastaLocusBuilderParameters.AnchorPointPositionInfo pointInfo = parameters.getAnchorPointPositionInfo(-referencePointPosition + PATTERN_LINK_OFFSET);

            // Unpacking information from object
            referencePointPosition = pointInfo.position;

            // Matching pattern
            Matcher matcher = pointInfo.nucleotidePatternP.matcher(seqWithPositionMapping.getModifiedString());
            if (matcher.find())
                return matcher.start(1);

            if (referencePointPosition == USE_ONLY_PATTERN)
                // Don't try position guided search
                return -1;
        }

        // Normal position conversion
        return seqWithPositionMapping.convertPosition(referencePointPosition);
    }

    public void compile() {
        // Compiling all genes one by one
        List<String> toRemove = new ArrayList<>();
        for (GeneInfo gene : genes.values())
            if (!gene.compile())
                toRemove.add(gene.geneName);

        // Removing genes that failed to compile
        for (String r : toRemove) {
            warning(r + " removed.");
            genes.remove(r);
        }
    }

    public void printReport() {
        if (parameters.doAlignAlleles()) {
            finalReportStream.println();
            for (GeneInfo gene : genes.values()) {
                String geneName = gene.geneName;
                finalReportStream.println(geneName);
                finalReportStream.println(StringUtil.chars('=', geneName.length()));
                AlleleInfo ref = gene.finalList.get(0);
                NucleotideSequence refSeqNt = ref.baseSequence;

                // Collecting noncoding ranges information
                List<Range> noncodingRegionsL = new ArrayList<>();
                int[] orfRefPoints = ref.referencePoints.clone();
                TObjectIntMap<ReferencePoint> refMapping = parameters.getReferencePointIndexMapping();
                for (GeneFeature noncodingFeature : GeneFeature.NONCODING_FEATURES) {
                    int from = refMapping.get(noncodingFeature.getFirstPoint());
                    int to = refMapping.get(noncodingFeature.getLastPoint());
                    if (from == -1 || to == -1 || ref.referencePoints[from] == -1 || ref.referencePoints[to] == -1)
                        continue;

                    orfRefPoints[from] = -1;
                    orfRefPoints[to] = -1;

                    noncodingRegionsL.add(new Range(ref.referencePoints[from], ref.referencePoints[to]));
                }
                Range[] noncodingRegions = noncodingRegionsL.toArray(new Range[noncodingRegionsL.size()]);
                Arrays.sort(noncodingRegions, Range.COMPARATOR_BY_FROM);
                for (int i = 0; i < orfRefPoints.length; i++) {
                    int offset = 0;
                    for (Range noncodingRegion : noncodingRegions)
                        if (noncodingRegion.getTo() < orfRefPoints[i])
                            offset += noncodingRegion.length();
                    orfRefPoints[i] = Math.max(-1, orfRefPoints[i] - offset);
                }
                NucleotideSequence refSeqNtORF = refSeqNt;
                for (int i = noncodingRegions.length - 1; i >= 0; --i) {
                    Range range = noncodingRegions[i];
                    refSeqNtORF = refSeqNtORF.getRange(0, range.getFrom()).concatenate(refSeqNtORF.getRange(range.getTo(), refSeqNtORF.size()));
                }

                boolean translate = parameters.getTranslationReferencePointIndex() != -1 &&
                        orfRefPoints[parameters.getTranslationReferencePointIndex()] != -1;
                TranslationParameters translationParameters = null;
                AminoAcidSequence refSeqAA = null;
                if (translate) {
                    translationParameters = withIncompleteCodon(orfRefPoints[parameters.getTranslationReferencePointIndex()]);
                    refSeqAA = AminoAcidSequence.translate(refSeqNtORF, translationParameters);
                }

                List<Alignment<NucleotideSequence>> alignmentsNt = new ArrayList<>();
                List<Alignment<AminoAcidSequence>> alignmentsAA = new ArrayList<>();
                List<String> alleleNamesNt = new ArrayList<>(), alleleNamesAA = new ArrayList<>();
                for (int i = 1; i < gene.finalList.size(); i++) {
                    AlleleInfo a = gene.finalList.get(i);

                    Range refRange = new Range(ref.referencePoints[a.firstRefRefPoint], ref.referencePoints[a.lastRefRefPoint]);
                    Mutations<NucleotideSequence> absoluteMutations = a.mutations.move(refRange.getFrom());
                    alignmentsNt.add(new Alignment<>(refSeqNt, absoluteMutations, refRange,
                            new Range(0, refRange.length() + a.mutations.getLengthDelta()), parameters.getScoring()));
                    alleleNamesNt.add(a.getFullName());

                    if (!translate || orfRefPoints[a.firstRefRefPoint] == -1 || orfRefPoints[a.lastRefRefPoint] == -1)
                        continue;

                    //Range refRangeORF = new Range(orfRefPoints[a.firstRefRefPoint], orfRefPoints[a.lastRefRefPoint]);
                    Mutations<AminoAcidSequence> aaMuts = MutationsUtil.nt2aa(refSeqNtORF,
                            absoluteMutations.removeMutationsInRanges(noncodingRegions),
                            translationParameters, 10);
                    if (aaMuts == null)
                        continue;
                    Range refRangeAA = new Range(
                            convertNtPositionToAA(orfRefPoints[a.firstRefRefPoint], refSeqNt.size(), translationParameters).aminoAcidPosition,
                            convertNtPositionToAA(orfRefPoints[a.lastRefRefPoint] - 1, refSeqNt.size(), translationParameters).aminoAcidPosition + 1);
                    alignmentsAA.add(new Alignment<>(refSeqAA, aaMuts, refRangeAA,
                            new Range(0, refRangeAA.length() + aaMuts.getLengthDelta()), 0));
                    alleleNamesAA.add(a.getFullName());
                }

                // Nucleotide Alignment
                MultiAlignmentHelper multiAlignmentHelper =
                        MultiAlignmentHelper.build(MultiAlignmentHelper.DOT_MATCH_SETTINGS,
                                new Range(ref.getFirstReferencePointPosition(), ref.getLastReferencePointPosition()),
                                ref.baseSequence, alignmentsNt.toArray(new Alignment[alignmentsNt.size()]));
                multiAlignmentHelper.setSubjectLeftTitle(" " + ref.getFullName());

                for (int i = 0; i < alignmentsNt.size(); i++)
                    multiAlignmentHelper.setQueryLeftTitle(i, " " + alleleNamesNt.get(i));
                VDJCAlignmentsFormatter.drawPoints(multiAlignmentHelper, new SeqPartitioning(refMapping, ref.referencePoints, false), VDJCAlignmentsFormatter.POINTS_FOR_GERMLINE);
                for (MultiAlignmentHelper ah : multiAlignmentHelper.split(LINE_WIDTH, true)) {
                    finalReportStream.println(ah);
                    finalReportStream.println();
                }

                finalReportStream.println();

                // Don't print additional blank line and AA alignments if this information is not available for this gene
                if (!translate)
                    continue;

                finalReportStream.println(" **********");
                finalReportStream.println();

                // AminoAcid Alignment
                multiAlignmentHelper =
                        MultiAlignmentHelper.build(MultiAlignmentHelper.DOT_MATCH_SETTINGS,
                                new Range(0, refSeqAA.size()),
                                refSeqAA, alignmentsAA.toArray(new Alignment[alignmentsAA.size()]));
                multiAlignmentHelper.setSubjectLeftTitle(" " + ref.getFullName());
                for (int i = 0; i < alignmentsAA.size(); i++)
                    multiAlignmentHelper.setQueryLeftTitle(i, " " + alleleNamesAA.get(i));
                VDJCAlignmentsFormatter.drawPoints(multiAlignmentHelper, new SeqPartitioning(refMapping, orfRefPoints, true), VDJCAlignmentsFormatter.POINTS_FOR_GERMLINE);
                for (MultiAlignmentHelper ah : multiAlignmentHelper.split(LINE_WIDTH, true)) {
                    finalReportStream.println(ah);
                    finalReportStream.println();
                }

                finalReportStream.println();
            }
        }
    }

    public boolean checkLocus(String locusName) {
        if (locus == Locus.TRA || locus == Locus.TRD)
            return "TRA".equalsIgnoreCase(locusName) || "TRD".equalsIgnoreCase(locusName);
        return locus.toString().equalsIgnoreCase(locusName);
    }

    public boolean checkAllele(AlleleInfo alleleInfo) {
        if (!alleleInfo.isFunctional)
            return true;

        if (parameters.getGeneType() == GeneType.Variable) {
            int index = parameters.getReferencePointIndexMapping().get(ReferencePoint.CDR3Begin);
            if (index == -1)
                throw new RuntimeException();
            int position = alleleInfo.referencePoints[index];
            if (position < 0 || alleleInfo.baseSequence.size() < position + 3) {
                warning("absent conserved Cys in functional allele " + alleleInfo.alleleName);
                return false;
            }
            byte aa = GeneticCode.getAminoAcid(getTriplet(alleleInfo.baseSequence, position));
            if (aa != AminoAcidAlphabet.C) {
                warning(AminoAcidSequence.ALPHABET.codeToSymbol(aa) + " instead of conserved Cys" +
                        " in functional allele " + alleleInfo.alleleName);
                return false;
            }
        }
        return true;
    }

    public void writeAlleles(LociLibraryWriter writer) throws IOException {
        for (GeneInfo gene : genes.values()) {
            for (AlleleInfo alleleInfo : gene.finalList) {
                if (alleleInfo.isReference) {
                    String accession = UUID.randomUUID().toString() + "-" + alleleInfo.alleleName;
                    writer.writeSequencePart(accession, 0, alleleInfo.baseSequence);
                    writer.writeAllele(parameters.getGeneType(), alleleInfo.alleleName, true,
                            alleleInfo.isFunctional, accession, alleleInfo.referencePoints, null, null, null);
                } else
                    writer.writeAllele(parameters.getGeneType(), alleleInfo.alleleName, false,
                            alleleInfo.isFunctional, null, null, alleleInfo.reference.alleleName,
                            alleleInfo.mutations.getRAWMutations(), alleleInfo.getReferenceGeneFeature());
            }
        }
    }

    private static final class SeqPartitioning extends SequencePartitioning {
        final TObjectIntMap<ReferencePoint> refMapping;
        final int[] referencePoints;
        final boolean aa;

        public SeqPartitioning(TObjectIntMap<ReferencePoint> refMapping, int[] referencePoints, boolean aa) {
            this.refMapping = refMapping;
            this.referencePoints = referencePoints;
            this.aa = aa;
        }

        @Override
        public int getPosition(ReferencePoint referencePoint) {
            int rp = refMapping.get(referencePoint.getWithoutOffset());
            if (rp == -1)
                return -1;
            rp = referencePoints[rp] + referencePoint.getOffset();
            if (rp < 0)
                return -1;
            return aa ? (rp + 2) / 3 : rp;
        }
    }

    private final class GeneInfo {
        final String geneName;
        final Map<String, AlleleInfo> alleles = new HashMap<>();
        final List<AlleleInfo> finalList = new ArrayList<>();
        AlleleInfo reference;
        boolean severalReferenceAlleles = false;

        public GeneInfo(String geneName) {
            this.geneName = geneName;
        }

        public boolean compile() {
            if (parameters.doAlignAlleles()) {
                // Find reference allele
                AlleleInfo reference = this.reference;

                // Checks
                if (!reference.isReference && !parameters.firstOccurredAlleleIsReference()) {
                    errorOrException("No reference allele for gene " + geneName + ". Sipping.");
                    return false;
                }
                if (severalReferenceAlleles) {
                    errorOrException("Several reference alleles for " + geneName + ". Sipping.");
                    return false;
                }

                reference.isReference = true;

                // Compiling final alleles list
                finalList.add(reference);
                for (AlleleInfo allele : alleles.values())
                    if (allele != reference) {
                        allele.reference = reference;
                        NucleotideSequence seq1 = reference.baseSequence;
                        NucleotideSequence seq2 = allele.baseSequence;
                        int[] ref1 = reference.referencePoints;
                        int[] ref2 = allele.referencePoints;
                        MutationsBuilder<NucleotideSequence> mutations = new MutationsBuilder<>(NucleotideSequence.ALPHABET);
                        int prev1 = -1, prev2 = -1, curr1, curr2;
                        int firstRefRefPoint = -1, lastRefRefPoint = -1;

                        for (int i = 0; i < ref1.length; i++) {
                            curr1 = ref1[i];
                            curr2 = ref2[i];

                            if (curr1 == -1 || curr2 == -1)
                                continue;

                            if (firstRefRefPoint == -1)
                                firstRefRefPoint = i;
                            lastRefRefPoint = i;

                            if (prev1 == -1) {
                                prev1 = curr1;
                                prev2 = curr2;
                                continue;
                            }

                            if (curr1 - prev1 == 0 && curr2 - prev2 == 0)
                                continue;

                            Alignment<NucleotideSequence> alignment = Aligner.alignGlobal(parameters.getScoring(),
                                    seq1.getRange(prev1, curr1), seq2.getRange(prev2, curr2));
                            mutations.append(alignment.getAbsoluteMutations().move(prev1));
                            prev1 = curr1;
                            prev2 = curr2;
                        }

                        allele.firstRefRefPoint = firstRefRefPoint;
                        allele.lastRefRefPoint = lastRefRefPoint;
                        allele.mutations = mutations.createAndDestroy().move(-ref1[firstRefRefPoint]);
                        finalList.add(allele);
                    }

                Collections.sort(finalList, new Comparator<AlleleInfo>() {
                    @Override
                    public int compare(AlleleInfo o1, AlleleInfo o2) {
                        if (o1.isReference)
                            return -1;
                        if (o2.isReference)
                            return 1;
                        return o1.alleleName.compareTo(o2.alleleName);
                    }
                });

                // Success
                return true;
            } else {
                // Just creates final list
                finalList.add(reference);
                for (AlleleInfo allele : alleles.values())
                    if (allele != reference)
                        finalList.add(allele);
                return true;
            }
        }
    }

    private final class AlleleInfo {
        final String geneName, alleleName;
        final NucleotideSequence baseSequence;
        boolean isFunctional, isReference;
        final int[] referencePoints;
        int firstRefRefPoint, lastRefRefPoint;
        AlleleInfo reference;
        Mutations<NucleotideSequence> mutations;

        public AlleleInfo(String geneName, String alleleName, NucleotideSequence baseSequence,
                          boolean isFunctional, boolean isReference, int[] referencePoints) {
            this.geneName = geneName;
            this.alleleName = alleleName;
            this.baseSequence = baseSequence;
            this.isFunctional = isFunctional;
            this.isReference = isReference;
            this.referencePoints = referencePoints;
        }

        public String getFullName() {
            return alleleName + " [" + (isFunctional ? "F" : "P") + "]";
        }

        public int getFirstReferencePointPosition() {
            for (int referencePoint : referencePoints)
                if (referencePoint != -1)
                    return referencePoint;
            return -1;
        }

        public int getLastReferencePointPosition() {
            int result = -1;
            for (int referencePoint : referencePoints)
                if (referencePoint != -1)
                    result = referencePoint;
            return result;
        }

        public GeneFeature getReferenceGeneFeature() {
            ReferencePoint[] mapping = parameters.getIndexReferencePointMapping();
            return new GeneFeature(mapping[firstRefRefPoint], mapping[lastRefRefPoint]);
        }
    }
}
