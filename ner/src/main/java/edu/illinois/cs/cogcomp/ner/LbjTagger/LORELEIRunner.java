package edu.illinois.cs.cogcomp.ner.LbjTagger;

import edu.illinois.cs.cogcomp.core.datastructures.Pair;
import edu.illinois.cs.cogcomp.core.datastructures.ViewNames;
import edu.illinois.cs.cogcomp.core.datastructures.textannotation.Constituent;
import edu.illinois.cs.cogcomp.core.datastructures.textannotation.Sentence;
import edu.illinois.cs.cogcomp.core.datastructures.textannotation.TextAnnotation;
import edu.illinois.cs.cogcomp.core.datastructures.textannotation.View;
import edu.illinois.cs.cogcomp.core.io.IOUtils;
import edu.illinois.cs.cogcomp.core.io.LineIO;
import edu.illinois.cs.cogcomp.core.utilities.SerializationHelper;
import edu.illinois.cs.cogcomp.core.utilities.StringUtils;
import edu.illinois.cs.cogcomp.lbjava.classify.TestDiscrete;
import edu.illinois.cs.cogcomp.lbjava.learn.*;
import edu.illinois.cs.cogcomp.lbjava.parse.LinkedChild;
import edu.illinois.cs.cogcomp.lbjava.parse.LinkedVector;
import edu.illinois.cs.cogcomp.lbjava.parse.Parser;
import edu.illinois.cs.cogcomp.lbjava.parse.WeightedParser;
import edu.illinois.cs.cogcomp.ner.ExpressiveFeatures.ExpressiveFeaturesAnnotator;
import edu.illinois.cs.cogcomp.ner.ExpressiveFeatures.TwoLayerPredictionAggregationFeatures;
import edu.illinois.cs.cogcomp.ner.InferenceMethods.Decoder;
import edu.illinois.cs.cogcomp.ner.InferenceMethods.PredictionsAndEntitiesConfidenceScores;
import edu.illinois.cs.cogcomp.ner.LbjFeatures.NETaggerLevel1;
import edu.illinois.cs.cogcomp.ner.LbjFeatures.NETaggerLevel2;
import edu.illinois.cs.cogcomp.ner.ParsingProcessingData.TaggedDataReader;
import edu.illinois.cs.cogcomp.ner.ParsingProcessingData.TaggedDataWriter;
import edu.illinois.cs.cogcomp.ner.WordEmbedding;
import org.apache.commons.cli.*;
import org.apache.commons.io.FilenameUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.xml.soap.Text;
import java.io.File;
import java.io.IOException;
import java.util.*;

/**
 * LORELEIRunner class. For LORELEI 2018
 * Created by mayhew2 on 3/15/16.
 */
@SuppressWarnings("Duplicates")
public class LORELEIRunner {

    private static final Logger logger = LoggerFactory.getLogger(LORELEIRunner.class);
    public static String filesFormat = "-c";
    private static boolean dupe = false;

    public static String config = null;

    public static void main(String[] args) throws Exception {

        Options options = new Options();
        Option help = new Option( "help", "print this message" );

        Option configfile = Option.builder("cf")
                .hasArg()
                .required()
                .build();

        Option trainpath = Option.builder("train")
                .hasArg()
                .build();

        Option testpath = Option.builder("test")
                .hasArg()
                .required()
                .build();

        Option langopt = Option.builder("lang")
                .hasArg()
                .required()
                .build();

        Option formatopt = Option.builder("format")
                .hasArg()
                .desc("Choose between reading conll files and reading from serialized TAs")
                .build();


        options.addOption(help);
        options.addOption(trainpath);
        options.addOption(testpath);
        options.addOption(langopt);
        options.addOption(formatopt);

        options.addOption(configfile);

        CommandLineParser parser = new DefaultParser();
        CommandLine cmd = parser.parse(options, args);

        if(cmd.hasOption("cf")){
            config = cmd.getOptionValue("cf");
        }

        boolean areWeTraining = cmd.hasOption("train");
        Parameters.readConfigAndLoadExternalData(config, areWeTraining);
        String modelpath = ParametersForLbjCode.currentParameters.pathToModelFile;
        if(modelpath.startsWith("tmp") || modelpath.length() == 0){
            Random r = new Random();
            modelpath = "/tmp/nermodel" + r.nextInt();
        }


        if(ParametersForLbjCode.currentParameters.featuresToUse.containsKey("Embedding")) {
            if(ParametersForLbjCode.currentParameters.testlang.equals("en"))
                WordEmbedding.setMonoVecsNew("en");
            else
                WordEmbedding.loadMultiDBNew(ParametersForLbjCode.currentParameters.testlang);
        }

        if(cmd.hasOption("format")){
            filesFormat = cmd.getOptionValue("format");
            System.out.println(filesFormat);
        }

        if(cmd.hasOption("train")){
            int trainiter = 30;
            String trainroot = cmd.getOptionValue("train");
            Data trainData = loaddata(trainroot, filesFormat, true);
            RunTraining(trainData, trainiter, modelpath);
            System.out.println("Trained on: " + trainroot);
        }

        // should be always... it's required.
        if(cmd.hasOption("test")){
            String testroot = cmd.getOptionValue("test");
            String lang = cmd.getOptionValue("lang");
            Data testData = loaddata(testroot, filesFormat, false);
            Pair<Double, Double> levels  = RunTest(testData, modelpath, lang);
            System.out.println("Tested on: " + testroot);
        }

    }

    /**
     * Given a path to a folder, load the data from that folder. Assume the string is a comma
     * separated list, perhaps of length one.
     * @param datapath
     * @param filesFormat
     * @return
     * @throws Exception
     */
    public static Data loaddata(String datapath, String filesFormat, boolean train) throws Exception {
        String[] paths = datapath.split(",");
        Data data;

        if (filesFormat.equals("ta")) {
            List<TextAnnotation> tas = new ArrayList<>();
            for(String path : paths){
                File[] files = (new File(path)).listFiles();
                for(File file : files){
                    TextAnnotation ta = SerializationHelper.deserializeTextAnnotationFromFile(file.getPath());
                    tas.add(ta);
                }
            }
            data = loaddataFromTAs(tas);
        }else if(filesFormat.equals("-c")) {
            // Get train data
            String first = paths[0];
            data = new Data(first, first, filesFormat, new String[]{}, new String[]{});
            for (int i = 1; i < paths.length; i++) {
                data.addFolderToData(paths[i], filesFormat);
            }
        }else{
            throw new IllegalArgumentException("format " + filesFormat + " not recognized");
        }

        ExpressiveFeaturesAnnotator.train = train;
        ExpressiveFeaturesAnnotator.annotate(data);
        data.setLabelsToIgnore(ParametersForLbjCode.currentParameters.labelsToIgnoreInEvaluation);

        return data;
    }

    /**
     * NER Code uses the Data object to run. This converts TextAnnotations into a Data object.
     * Important: this creates data with BIO labeling.
     *
     * @param tas list of text annotations
     */
    public static Data loaddataFromTAs(List<TextAnnotation> tas) throws Exception {

        Data data = new Data();
        for(TextAnnotation ta : tas) {
            // convert this data structure into one the NER package can deal with.
            ArrayList<LinkedVector> sentences = new ArrayList<>();
            String[] tokens = ta.getTokens();

            View ner = ta.getView(ViewNames.NER_CONLL);

            int[] tokenindices = new int[tokens.length];
            int tokenIndex = 0;
            int neWordIndex = 0;
            for (int i = 0; i < ta.getNumberOfSentences(); i++) {
                Sentence sentence = ta.getSentence(i);
                int sentstart = sentence.getStartSpan();

                LinkedVector words = new LinkedVector();

                for(int k = 0; k < sentence.size(); k++){
                    String w = sentence.getToken(k);

                    int tokenid = sentstart+k;
                    List<Constituent> cons = ner.getConstituentsCoveringToken(tokenid);
                    if(cons.size() > 1){
                        logger.error("Too many constituents for token " + tokenid + ", choosing just the first.");
                    }

                    String tag = "O";

                    if(cons.size() > 0) {
                        Constituent c = cons.get(0);
                        if(tokenid == c.getSpan().getFirst())
                            tag = "B-" + c.getLabel();
                        else
                            tag = "I-" + c.getLabel();
                    }

                    if (w.length() > 0) {
                        NEWord.addTokenToSentence(words, w, tag);
                        tokenindices[neWordIndex] = tokenIndex;
                        neWordIndex++;
                    } else {
                        logger.error("Bad (zero length) token.");
                    }
                    tokenIndex++;
                }
                if (words.size() > 0)
                    sentences.add(words);
            }
            NERDocument doc = new NERDocument(sentences, ta.getId());
            data.documents.add(doc);
        }

        return data;
    }

    /**
     * @param classifier
     * @param dataSet
     * @param exampleStorePath
     * @return
     */
    private static WeightedBatchTrainer prefetchAndGetBatchTrainer(SparseNetworkLearner classifier, Data dataSet, String exampleStorePath) {
        //logger.info("Pre-extracting the training data for Level 1 classifier");

        TextChunkRepresentationManager.changeChunkRepresentation(
                TextChunkRepresentationManager.EncodingScheme.BIO,
                ParametersForLbjCode.currentParameters.taggingEncodingScheme,
                dataSet,
                NEWord.LabelToLookAt.GoldLabel);

        // FIXME: this is where we set the progressOutput var for the BatchTrainer
        WeightedBatchTrainer bt = new WeightedBatchTrainer(classifier, new DataSetReader(dataSet, dupe), 50000);

        classifier.setLexicon(bt.preExtract(exampleStorePath));

        return bt;
    }

    /**
     * Given preloaded data, train a model and save it.
     * @param trainData
     * @param fixedNumIterations
     * @return
     * @throws Exception
     */
    public static void RunTraining(Data trainData, int fixedNumIterations, String modelPath) throws Exception {

        if ( IOUtils.exists( modelPath ) )
        {
            if ( !IOUtils.isDirectory( modelPath ) )
            {
                String msg = "ERROR: model directory '" + modelPath +
                        "' already exists as a (non-directory) file.";
                logger.error( msg );
                throw new IOException( msg );
            }
            else {
                logger.warn("deleting existing model path '" + modelPath + "'...");
                IOUtils.rmDir(modelPath);
                IOUtils.rm(modelPath + ".level1");
                IOUtils.rm(modelPath + ".level1.lex");
                IOUtils.rm(modelPath + ".level2");
            }
        }
        else
        {
            IOUtils.mkdir( modelPath );
        }


        IOUtils.cp(config, modelPath + "/" + FilenameUtils.getName(config));

        NETaggerLevel1 tagger1 = new NETaggerLevel1(modelPath + ".level1", modelPath + ".level1.lex");
        tagger1.forget();
        //NETaggerLevel1 tagger1 = new NETaggerLevel1();

        if (ParametersForLbjCode.currentParameters.featuresToUse.containsKey("PredictionsLevel1")) {
            PredictionsAndEntitiesConfidenceScores.getAndMarkEntities(trainData, NEWord.LabelToLookAt.GoldLabel);
            TwoLayerPredictionAggregationFeatures.setLevel1AggregationFeatures(trainData, true);
        }

        logger.info("Pre-extracting the training data for Level 1 classifier");
        String arg = modelPath + ".level1.prefetchedTrainData";
        WeightedBatchTrainer bt1train = prefetchAndGetBatchTrainer(tagger1, trainData, arg);

        logger.info("Training...");
        bt1train.train(fixedNumIterations);

        NETaggerLevel2 tagger2 = new NETaggerLevel2(modelPath + ".level2", modelPath + ".level2.lex");
        tagger2.forget();

        // Previously checked if PatternFeatures was in featuresToUse.
        if (ParametersForLbjCode.currentParameters.featuresToUse.containsKey("PredictionsLevel1")) {

            logger.info("Pre-extracting the training data for Level 2 classifier");
            WeightedBatchTrainer bt2train = prefetchAndGetBatchTrainer(tagger2, trainData, modelPath + ".level2.prefetchedTrainData");

            bt2train.train(fixedNumIterations);

        }

        for(Object o : tagger1.getNetwork().toArray()){
            WeightedSparseAveragedPerceptron wsap = (WeightedSparseAveragedPerceptron) o;
            System.out.println("Bias: " + wsap.getBias());

        };

        logger.info("Saving model to path: " + modelPath);
        tagger1.save();
        tagger2.save();
    }

    public static Pair<Double, Double> RunTest(Data testData, String modelPath, String testlang) throws Exception {

        NETaggerLevel1 tagger1 = new NETaggerLevel1(modelPath + ".level1", modelPath + ".level1.lex");
        NETaggerLevel2 tagger2 = new NETaggerLevel2(modelPath + ".level2", modelPath + ".level2.lex");

        Decoder.annotateDataBIO(testData, tagger1, tagger2);

        ArrayList<String> results = new ArrayList<>();
        for(NERDocument doc : testData.documents) {
            List<String> docpreds = new ArrayList<>();

            ArrayList<LinkedVector> sentences = doc.sentences;
            //results.add(doc.docname);
            for (int k = 0; k < sentences.size(); k++){
                for (int i = 0; i < sentences.get(k).size() ; ++i){
                    NEWord w = (NEWord)sentences.get(k).get(i);
                    if(!w.neLabel.equals(w.neTypeLevel2)) {
                        results.add(w.form + " " + w.neLabel + " " + w.neTypeLevel2);
                    }else{
                        results.add(w.form + " " + w.neLabel + " " + w.neTypeLevel2);
                    }
                    docpreds.add(ACLRunner.conllline(w.neTypeLevel2, i, w.form));
                }
                results.add("");
                docpreds.add("");
            }

            //LineIO.write("/shared/corpora/ner/system-outputs/"+testlang+"/projection-fa/" + doc.docname, docpreds);
        }
        //logger.info("Just wrote to: " + "/shared/corpora/ner/system-outputs/"+testlang+ "/projection-fa/");


        String outdatapath = modelPath + ".testdata";
        TaggedDataWriter.writeToFile(outdatapath, testData, "-c", NEWord.LabelToLookAt.PredictionLevel2Tagger);

        LineIO.write("gold-pred-" + testlang + ".txt", results);

        TestDiscrete resultsPhraseLevel1 = new TestDiscrete();
        resultsPhraseLevel1.addNull("O");
        TestDiscrete resultsTokenLevel1 = new TestDiscrete();
        resultsTokenLevel1.addNull("O");

        TestDiscrete resultsPhraseLevel2 = new TestDiscrete();
        resultsPhraseLevel2.addNull("O");
        TestDiscrete resultsTokenLevel2 = new TestDiscrete();
        resultsTokenLevel2.addNull("O");

        TestDiscrete resultsByBILOU = new TestDiscrete();
        TestDiscrete resultsSegmentation = new TestDiscrete();
        resultsByBILOU.addNull("O");
        resultsSegmentation.addNull("O");

        NETesterMultiDataset.reportPredictions(testData,
                resultsTokenLevel1,
                resultsTokenLevel2,
                resultsPhraseLevel1,
                resultsPhraseLevel2,
                resultsByBILOU,
                resultsSegmentation);

        System.out.println("------------------------------------------------------------");
        System.out.println("******	Combined performance on all the datasets :");
        System.out.println("\t>>> Dataset path : \t" + testData.datasetPath);
        System.out.println("------------------------------------------------------------");

        System.out.println("Phrase-level Acc Level2:");
        resultsPhraseLevel2.printPerformance(System.out);
        System.out.println("Token-level Acc Level2:");
        resultsTokenLevel2.printPerformance(System.out);
        System.out.println("Phrase-level Acc Level1:");
        resultsPhraseLevel1.printPerformance(System.out);
        System.out.println("Token-level Acc Level1:");
        resultsTokenLevel1.printPerformance(System.out);

        System.out.println("------------------------------------------------------------");
        System.out.println("\t Level 1 F1 Phrase-level: " + resultsPhraseLevel1.getOverallStats()[2]);
        System.out.println("\t Level 2 F1 Phrase-level: " + resultsPhraseLevel2.getOverallStats()[2]);

        System.out.println("------------------------------------------------------------");
        System.out.println("************************************************************");
        System.out.println("------------------------------------------------------------");

        return new Pair<>(resultsPhraseLevel1.getOverallStats()[2], resultsPhraseLevel2.getOverallStats()[2]);

    }

    public static class DataSetReader extends WeightedParser {
        public Data dataset = null;
        int docid = 0;
        int sentenceId =0;
        int tokenId=0;
        int generatedSamples = 0;
        private boolean readytoduplicate = true;
        private boolean duplicateO = false;

        public DataSetReader(Data dataset) {
            this.dataset = dataset;
        }

        public DataSetReader(Data dataset, boolean dupe) {
            this.dataset = dataset;
            this.duplicateO = dupe;
        }


        public void close() {
            // do nothing
        }

        public Object next() {
            if(docid >= dataset.documents.size()){
                return null;
            }

            NEWord res =  (NEWord) dataset.documents.get(docid).sentences.get(sentenceId).get(tokenId);

            if(duplicateO) {
                if (res.neLabel.equals("O") && readytoduplicate) {
                    NEWord clone = (NEWord) res.clone();
                    clone.neLabel = "B-MNT";
                    clone.weight = 1 - clone.weight;
                    readytoduplicate = false;
                    return res;
                } else {
                    readytoduplicate = true;
                }
            }

            if(tokenId < dataset.documents.get(docid).sentences.get(sentenceId).size()-1)
                tokenId++;
            else {
                tokenId=0;
                if(sentenceId<dataset.documents.get(docid).sentences.size()-1) {
                    sentenceId++;
                } else {
                    sentenceId=0;
                    docid++;
                }
            }
            generatedSamples++;

            return res;
        }
        public void reset() {
            sentenceId =0;
            tokenId=0;
            generatedSamples = 0;
            docid=0;
        }
    }


}
