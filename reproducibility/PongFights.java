import io.github.ericmedvet.jgea.core.InvertibleMapper;
import io.github.ericmedvet.jnb.core.NamedBuilder;
import io.github.ericmedvet.jnb.datastructure.DoubleRange;
import io.github.ericmedvet.jnb.datastructure.FormattedNamedFunction;
import io.github.ericmedvet.jnb.datastructure.Pair;
import io.github.ericmedvet.jsdynsym.control.BiSimulation;
import io.github.ericmedvet.jsdynsym.control.HomogeneousBiAgentTask;
import io.github.ericmedvet.jsdynsym.control.pong.PongAgent;
import io.github.ericmedvet.jsdynsym.control.pong.PongDrawer;
import io.github.ericmedvet.jsdynsym.control.pong.PongEnvironment;
import io.github.ericmedvet.jsdynsym.core.numerical.NumericalDynamicalSystem;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

public class PongFights {
  private static final NamedBuilder<Object> BUILDER = NamedBuilder.fromDiscovery();
  
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws IOException {
    
    String folder = "/home/il_bello/IdeaProjects/results/ss-roarnet/";
    String CSVPath1 = folder + "allBest_bi.csv";
    String CSVPath2 = folder + "allBest_trainer.csv";
    String delimiter = ";";
    boolean singleCSV = false;
    boolean saveVideo = false;
    
    String videoFolder = folder + "videos/";
    if (saveVideo) {
      Files.createDirectories(Paths.get(videoFolder));
    }
    
    String score1 = "ds.e.pong.shiftedScoreDiff1()";
    String score2 = "ds.e.pong.shiftedScoreDiff2()";
    String numberCollisionsWithBall1 = "ds.e.pong.numberOfCollisionsWithBall1()";
    String numberCollisionsWithBall2 = "ds.e.pong.numberOfCollisionsWithBall2()";
    
    Map<String, Integer> opponentIndices = new HashMap<>();
    List<String> opponentNames = new ArrayList<>();
    List<Pair<String, NumericalDynamicalSystem<?>>> opponents = new ArrayList<>();
    int maxSeed = 0;
    
    List<String> lines1 = Files.readAllLines(Paths.get(CSVPath1));
    List<String> lines2 = new ArrayList<>();
    if (!singleCSV) {
      lines2 = Files.readAllLines(Paths.get(CSVPath2));
    }
    
    if (!singleCSV) {
      System.out.println("Removing header, check if correct: " + lines2.getFirst());
      lines2.removeFirst();
    }
    
    List<String> mergedLines = new ArrayList<>(lines1);
    if (!singleCSV) {
      mergedLines.addAll(lines2);
    }
    
    String mergedCSVContent = String.join(System.lineSeparator(), mergedLines);
    
    try (Reader mergedReader = new StringReader(mergedCSVContent)) {
      CSVParser csvParser = CSVFormat.Builder.create()
          .setDelimiter(delimiter)
          .build()
          .parse(mergedReader);
      
      List<CSVRecord> records = csvParser.getRecords();
      CSVRecord headerCSV = records.getFirst();
      
      int mapperColumnIndex = 0, genotypeColumnIndex = 0, nameColumnIndex = 0, seedColumnIndex = 0;
      for (int i = 0; i < headerCSV.size(); i++) {
        String columnName = headerCSV.get(i);
        if (columnName.contains("mapper")) {
          mapperColumnIndex = i;
        } else if (columnName.contains("genotype")) {
          genotypeColumnIndex = i;
        } else if (columnName.contains("name")) {
          nameColumnIndex = i;
        } else if (columnName.contains("seed")) {
          seedColumnIndex = i;
        }
      }
      
      Function<String, Object> deserializer = (Function<String, Object>) BUILDER.build("f.fromBase64()");
      PongEnvironment environment = (PongEnvironment) BUILDER.build("ds.e.pong()");
      NumericalDynamicalSystem<?> exampleNDS = environment.exampleAgent();
      
      for (int i = 1; i < records.size(); i++) {
        CSVRecord record = records.get(i);
        NumericalDynamicalSystem<?> opponent = ((InvertibleMapper<Object, NumericalDynamicalSystem<?>>) BUILDER.build(
            record.get(mapperColumnIndex)
        ))
            .mapperFor(exampleNDS)
            .apply(deserializer.apply(record.get(genotypeColumnIndex)));
        String name = record.get(nameColumnIndex);
        maxSeed = Math.max(maxSeed, Integer.parseInt(record.get(seedColumnIndex)));
        if (!opponentIndices.containsKey(name)) {
          opponentIndices.put(name, opponentNames.size());
          opponentNames.add(name);
        }
        opponents.add(new Pair<>(name, opponent));
      }
      
      opponentIndices.put("trainer", opponentNames.size());
      opponentNames.add("trainer");
      for (int i = 0; i < maxSeed; i++) {
        opponents.add(new Pair<>("trainer", new PongAgent()));
      }
      
      int numOpponents = opponentNames.size();
      String[][] scoreMatrix = new String[numOpponents][numOpponents];
      String[][] collisionsMatrix = new String[numOpponents][numOpponents];
      int[][] matchCounts = new int[numOpponents][numOpponents];
      double[][] totalScore1 = new double[numOpponents][numOpponents];
      double[][] totalScore2 = new double[numOpponents][numOpponents];
      double[][] totalCollisions1 = new double[numOpponents][numOpponents];
      double[][] totalCollisions2 = new double[numOpponents][numOpponents];
      
      Map<String, Integer> winsMap = new HashMap<>();
      Map<String, Integer> tieMap = new HashMap<>();
      Map<String, Integer> winsAgainstSimple = new HashMap<>();
      Map<String, Integer> tieAgainstSimple = new HashMap<>();
      Map<String, Integer> matchesPlayed = new HashMap<>();
      Map<String, Integer> matchesVsSimple = new HashMap<>();
      
      for (String name : opponentNames) {
        winsMap.put(name, 0);
        tieMap.put(name, 0);
        winsAgainstSimple.put(name, 0);
        tieAgainstSimple.put(name, 0);
        matchesPlayed.put(name, 0);
        matchesVsSimple.put(name, 0);
      }
      
      HomogeneousBiAgentTask<NumericalDynamicalSystem<?>, double[], double[], PongEnvironment.State> task = HomogeneousBiAgentTask
          .fromHomogenousBiEnvironment(
              () -> environment,
              s -> false,
              new DoubleRange(0, 60),
              0.05
          );
      
      for (Pair<String, NumericalDynamicalSystem<?>> opponent1 : opponents) {
        for (Pair<String, NumericalDynamicalSystem<?>> opponent2 : opponents) {
          int index1 = opponentIndices.get(opponent1.first());
          int index2 = opponentIndices.get(opponent2.first());
          
          BiSimulation.Outcome<HomogeneousBiAgentTask.Step<double[], double[], PongEnvironment.State>> matchOutcome = task
              .simulate(new Pair<>(opponent1.second(), opponent2.second()));
          
          double fitness1 = ((FormattedNamedFunction<BiSimulation.Outcome<HomogeneousBiAgentTask.Step<double[], double[], PongEnvironment.State>>, Double>) BUILDER
              .build(score1)).apply(matchOutcome);
          double fitness2 = ((FormattedNamedFunction<BiSimulation.Outcome<HomogeneousBiAgentTask.Step<double[], double[], PongEnvironment.State>>, Double>) BUILDER
              .build(score2)).apply(matchOutcome);
          double collisions1 = ((FormattedNamedFunction<BiSimulation.Outcome<HomogeneousBiAgentTask.Step<double[], double[], PongEnvironment.State>>, Double>) BUILDER
              .build(numberCollisionsWithBall1)).apply(matchOutcome);
          double collisions2 = ((FormattedNamedFunction<BiSimulation.Outcome<HomogeneousBiAgentTask.Step<double[], double[], PongEnvironment.State>>, Double>) BUILDER
              .build(numberCollisionsWithBall2)).apply(matchOutcome);
          
          totalScore1[index1][index2] += fitness1;
          totalScore2[index1][index2] += fitness2;
          totalCollisions1[index1][index2] += collisions1;
          totalCollisions2[index1][index2] += collisions2;
          matchCounts[index1][index2]++;
          
          matchesPlayed.put(opponent1.first(), matchesPlayed.get(opponent1.first()) + 1);
          matchesPlayed.put(opponent2.first(), matchesPlayed.get(opponent2.first()) + 1);
          
          if (opponent1.first().equals("trainer") && !opponent2.first().equals("trainer")) {
            matchesVsSimple.put(opponent2.first(), matchesVsSimple.get(opponent2.first()) + 1);
            if (fitness1 < fitness2 && saveVideo) {
              String videoPath = videoFolder + opponent1.first() + index1 + "_VS_" + opponent2
                  .first() + "__" + RandomGenerator.getDefault().nextInt() + ".mp4";
              PongDrawer pongDrawer = new PongDrawer();
              pongDrawer.videoBuilder().save(new File(videoPath), matchOutcome);
            }
          } else if (opponent2.first().equals("trainer") && !opponent1.first().equals("trainer")) {
            matchesVsSimple.put(opponent1.first(), matchesVsSimple.get(opponent1.first()) + 1);
            if (fitness1 > fitness2 && saveVideo) {
              String videoPath = videoFolder + opponent1.first() + index1 + "_VS_" + opponent2
                  .first() + "__" + RandomGenerator.getDefault().nextInt() + ".mp4";
              PongDrawer pongDrawer = new PongDrawer();
              pongDrawer.videoBuilder().save(new File(videoPath), matchOutcome);
            }
          }
          
          if (fitness1 > fitness2) {
            winsMap.put(opponent1.first(), winsMap.get(opponent1.first()) + 1);
            if (opponent2.first().equals("trainer") && !opponent1.first().equals("trainer")) {
              winsAgainstSimple.put(opponent1.first(), winsAgainstSimple.get(opponent1.first()) + 1);
            }
          } else if (fitness2 > fitness1) {
            winsMap.put(opponent2.first(), winsMap.get(opponent2.first()) + 1);
            if (opponent1.first().equals("trainer") && !opponent2.first().equals("trainer")) {
              winsAgainstSimple.put(opponent2.first(), winsAgainstSimple.get(opponent2.first()) + 1);
            }
          } else {
            tieMap.put(opponent1.first(), tieMap.get(opponent1.first()) + 1);
            tieMap.put(opponent2.first(), tieMap.get(opponent2.first()) + 1);
            if (opponent2.first().equals("trainer") && !opponent1.first().equals("trainer")) {
              tieAgainstSimple.put(opponent1.first(), tieAgainstSimple.get(opponent1.first()) + 1);
            } else if (opponent1.first().equals("trainer") && !opponent2.first().equals("trainer")) {
              tieAgainstSimple.put(opponent2.first(), tieAgainstSimple.get(opponent2.first()) + 1);
            }
          }
        }
      }
      
      for (int i = 0; i < numOpponents; i++) {
        for (int j = 0; j < numOpponents; j++) {
          if (matchCounts[i][j] > 0) {
            scoreMatrix[i][j] = " = " + (totalScore1[i][j] / matchCounts[i][j]) + " / " + (totalScore2[i][j] / matchCounts[i][j]);
            collisionsMatrix[i][j] = " = " + (totalCollisions1[i][j] / matchCounts[i][j]) + " / " + (totalCollisions2[i][j] / matchCounts[i][j]);
          }
        }
      }
      
      List<Map.Entry<String, Integer>> ranking = new ArrayList<>(winsMap.entrySet());
      ranking.sort((e1, e2) -> Double.compare(e2.getValue(), e1.getValue()));
      
      try (BufferedWriter rankingWriter = Files.newBufferedWriter(Paths.get(folder + "ranking.csv"))) {
        rankingWriter.write("nome;win_total;tie_total;win_trainer;tie_trainer;fights_total;fights_vs_trainer\n");
        for (Map.Entry<String, Integer> entry : ranking) {
          String agentName = entry.getKey();
          String vsSimpleWins = agentName.equals("trainer") ? "-" : String.valueOf(winsAgainstSimple.get(agentName));
          String totalMatches = String.valueOf(matchesPlayed.get(agentName));
          String matchesAgainstSimple = agentName.equals("trainer") ? "-" : String.valueOf(
              matchesVsSimple.get(agentName)
          );
          String tieSimple = agentName.equals("trainer") ? "-" : String.valueOf(tieAgainstSimple.get(agentName));
          String tieTotal = String.valueOf(tieMap.get(agentName));
          rankingWriter.write(
              agentName + ";" + entry
                  .getValue() + ";" + tieTotal + ";" + vsSimpleWins + ";" + tieSimple + ";" + totalMatches + ";" + matchesAgainstSimple + "\n"
          );
        }
      }
      
      try (BufferedWriter scoreWriter = Files.newBufferedWriter(
          Paths.get(folder + "scores-formula.csv")
      ); BufferedWriter collisionsWriter = Files.newBufferedWriter(Paths.get(folder + "collisions-formula.csv"))) {
        scoreWriter.write(";" + String.join(";", opponentNames) + "\n");
        collisionsWriter.write(";" + String.join(";", opponentNames) + "\n");
        for (int i = 0; i < numOpponents; i++) {
          scoreWriter.write(opponentNames.get(i) + ";" + String.join(";", scoreMatrix[i]) + "\n");
          collisionsWriter.write(opponentNames.get(i) + ";" + String.join(";", collisionsMatrix[i]) + "\n");
        }
      }
    }
  }
}
